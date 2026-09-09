package com.example.transfer.task;

import com.example.transfer.repository.TaskStatus;

/**
 * 并发迁移冲突：条件更新 {@code UPDATE ... WHERE id=? AND status=?} 影响行数为 0。
 *
 * <p>按 04 §3：视为并发冲突 → 放弃本次动作（不重试同一动作，交由下一轮调度重新判断）。</p>
 */
public class ConcurrentTransitionException extends RuntimeException {

    private final long taskId;
    private final TaskStatus expectedFrom;

    public ConcurrentTransitionException(long taskId, TaskStatus expectedFrom, TaskStatus to, String code) {
        super("transition conflict: task " + taskId + " no longer in " + expectedFrom
                + " (target " + to + ", code " + code + ")");
        this.taskId = taskId;
        this.expectedFrom = expectedFrom;
    }

    public long getTaskId() {
        return taskId;
    }

    public TaskStatus getExpectedFrom() {
        return expectedFrom;
    }
}
