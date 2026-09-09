package com.example.transfer.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.transfer.repository.TaskStatus;

/**
 * 状态机纯逻辑单测：迁移集合 = 04-state-machine.md §2（T1–T23）与 §3 非法迁移清单。
 */
class TaskStateMachineTest {

    private final TaskStateMachine machine = new TaskStateMachine();

    /** 04 §2「迁移矩阵速查」权威值。 */
    private static final Map<TaskStatus, Set<TaskStatus>> EXPECTED = Map.ofEntries(
            Map.entry(TaskStatus.DISCOVERED, EnumSet.of(TaskStatus.STABILITY_CHECK, TaskStatus.CANCELLED)),
            Map.entry(TaskStatus.STABILITY_CHECK,
                    EnumSet.of(TaskStatus.STABILITY_CHECK, TaskStatus.READY, TaskStatus.CANCELLED)),
            Map.entry(TaskStatus.READY, EnumSet.of(TaskStatus.UPLOADING, TaskStatus.CANCELLED)),
            Map.entry(TaskStatus.UPLOADING,
                    EnumSet.of(TaskStatus.S3_UPLOADED, TaskStatus.WAITING_RETRY, TaskStatus.READY)),
            Map.entry(TaskStatus.S3_UPLOADED, EnumSet.of(TaskStatus.GATEWAY_DELIVERING)),
            Map.entry(TaskStatus.GATEWAY_DELIVERING,
                    EnumSet.of(TaskStatus.DELIVERED, TaskStatus.WAITING_RETRY)),
            Map.entry(TaskStatus.WAITING_RETRY, EnumSet.of(TaskStatus.GATEWAY_DELIVERING,
                    TaskStatus.UPLOADING, TaskStatus.READY, TaskStatus.CANCELLED)),
            Map.entry(TaskStatus.DELIVERED, EnumSet.noneOf(TaskStatus.class)),
            Map.entry(TaskStatus.CANCELLED, EnumSet.noneOf(TaskStatus.class)));

    @Test
    void legalTargetsMatchesAuthoritativeQuickMatrix() {
        for (TaskStatus from : TaskStatus.values()) {
            assertEquals(EXPECTED.get(from), machine.legalTargets(from),
                    "legalTargets(" + from + ") 应与 04 §2 速查一致");
        }
    }

    @Test
    void everySpecIsExecutableAndSelfConsistent() {
        assertEquals(24, TaskStateMachine.SPECS.size());
        for (TaskTransitionSpec spec : TaskStateMachine.SPECS) {
            if (spec.from() == null) {
                // T1 初始创建
                assertEquals("T1", spec.code());
                assertEquals(TaskStatus.DISCOVERED, spec.to());
            } else {
                assertDoesNotThrow(() -> machine.require(spec.code(), spec.from(), spec.to()));
                assertTrue(EXPECTED.get(spec.from()).contains(spec.to()),
                        spec.code() + " 的 to 必须在速查表中");
            }
        }
    }

    @Test
    void codeFromToMismatchRejected() {
        // T4 只能从 STABILITY_CHECK → READY
        assertThrows(IllegalTransitionException.class,
                () -> machine.require("T4", TaskStatus.READY, TaskStatus.READY));
        assertThrows(IllegalTransitionException.class,
                () -> machine.require("T4", TaskStatus.STABILITY_CHECK, TaskStatus.CANCELLED));
        // 不存在的 code
        assertThrows(IllegalTransitionException.class,
                () -> machine.require("T99", TaskStatus.READY));
    }

    @Test
    void illegalTransitionListFromDocRejected() {
        List<TaskStatus[]> illegal = List.of(
                new TaskStatus[] {TaskStatus.DISCOVERED, TaskStatus.READY},
                new TaskStatus[] {TaskStatus.STABILITY_CHECK, TaskStatus.UPLOADING},
                new TaskStatus[] {TaskStatus.READY, TaskStatus.S3_UPLOADED},
                new TaskStatus[] {TaskStatus.READY, TaskStatus.GATEWAY_DELIVERING},
                new TaskStatus[] {TaskStatus.UPLOADING, TaskStatus.DELIVERED},
                new TaskStatus[] {TaskStatus.UPLOADING, TaskStatus.GATEWAY_DELIVERING},
                new TaskStatus[] {TaskStatus.WAITING_RETRY, TaskStatus.DELIVERED},
                new TaskStatus[] {TaskStatus.GATEWAY_DELIVERING, TaskStatus.UPLOADING},
                // 任意 → WAITING_RETRY 只允许 UPLOADING / GATEWAY_DELIVERING 出发
                new TaskStatus[] {TaskStatus.DISCOVERED, TaskStatus.WAITING_RETRY},
                new TaskStatus[] {TaskStatus.STABILITY_CHECK, TaskStatus.WAITING_RETRY},
                new TaskStatus[] {TaskStatus.READY, TaskStatus.WAITING_RETRY},
                new TaskStatus[] {TaskStatus.S3_UPLOADED, TaskStatus.WAITING_RETRY},
                new TaskStatus[] {TaskStatus.WAITING_RETRY, TaskStatus.WAITING_RETRY},
                new TaskStatus[] {TaskStatus.DELIVERED, TaskStatus.WAITING_RETRY},
                new TaskStatus[] {TaskStatus.CANCELLED, TaskStatus.WAITING_RETRY});
        for (TaskStatus[] pair : illegal) {
            assertFalse(machine.isLegal(pair[0], pair[1]), pair[0] + "→" + pair[1] + " 必须非法");
            assertThrows(IllegalTransitionException.class,
                    () -> machine.requireByStates(pair[0], pair[1]),
                    () -> pair[0] + "→" + pair[1] + " 应抛 IllegalTransitionException");
        }
    }

    @Test
    void terminalStatesCanNeverLeave() {
        for (TaskStatus target : TaskStatus.values()) {
            assertFalse(machine.isLegal(TaskStatus.DELIVERED, target));
            assertFalse(machine.isLegal(TaskStatus.CANCELLED, target));
        }
        assertThrows(IllegalTransitionException.class,
                () -> machine.requireByStates(TaskStatus.DELIVERED, TaskStatus.READY));
        assertThrows(IllegalTransitionException.class,
                () -> machine.requireByStates(TaskStatus.CANCELLED, TaskStatus.DISCOVERED));
    }

    @Test
    void nothingCanEnterDiscoveredExceptCreation() {
        for (TaskStatus from : TaskStatus.values()) {
            assertFalse(machine.isLegal(from, TaskStatus.DISCOVERED));
        }
        assertTrue(machine.isLegal(null, TaskStatus.DISCOVERED)); // T1
    }

    private static void assertDoesNotThrow(Executable exec) {
        try {
            exec.run();
        } catch (Throwable t) {
            throw new AssertionError("should not throw: " + t.getMessage(), t);
        }
    }

    @FunctionalInterface
    private interface Executable {
        void run() throws Throwable;
    }
}
