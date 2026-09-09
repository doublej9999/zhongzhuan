package com.example.transfer.task;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.example.transfer.repository.TaskStatus;

/**
 * 任务状态机（纯逻辑、无状态）：权威迁移集合 = 04-state-machine.md §2 T1–T23。
 *
 * <p>职责只做「合法性判定」：请求必须命中一条 {@code (code, from, to)} 迁移，否则抛
 * {@link IllegalTransitionException}。守卫条件（claim/lease/supersede 三条守卫等）不在
 * 本类判定，由调用方（Worker/SupersedeService 等）在各自 Phase 实现并先于本机校验。</p>
 */
@Component
public final class TaskStateMachine {

    /** T1–T21 全部合法迁移（T22/T23 为禁止项，不在此列出 → 一律拒绝）。 */
    public static final List<TaskTransitionSpec> SPECS = List.of(
            new TaskTransitionSpec("T1", null, TaskStatus.DISCOVERED, TaskTransitionEffect.NONE,
                    "初始创建（Scanner 建 file_version + task，非状态迁移）"),
            new TaskTransitionSpec("T2", TaskStatus.DISCOVERED, TaskStatus.STABILITY_CHECK,
                    TaskTransitionEffect.NONE, "首次观察记录完成，进入稳定判定"),
            new TaskTransitionSpec("T3", TaskStatus.STABILITY_CHECK, TaskStatus.STABILITY_CHECK,
                    TaskTransitionEffect.NONE, "fingerprint 变化，重置稳定基线（自迁移）"),
            new TaskTransitionSpec("T4", TaskStatus.STABILITY_CHECK, TaskStatus.READY,
                    TaskTransitionEffect.STABLE_FILE, "相邻两次 path+size+mtime 一致 → 稳定"),
            new TaskTransitionSpec("T5", TaskStatus.STABILITY_CHECK, TaskStatus.CANCELLED,
                    TaskTransitionEffect.CANCEL, "稳定前文件消失 / 目录禁用"),
            new TaskTransitionSpec("T6", TaskStatus.READY, TaskStatus.UPLOADING,
                    TaskTransitionEffect.CLAIM, "Worker claim（同一事务写 lease）"),
            new TaskTransitionSpec("T7", TaskStatus.READY, TaskStatus.CANCELLED,
                    TaskTransitionEffect.CANCEL, "读取前文件消失 / 目录禁用"),
            new TaskTransitionSpec("T8", TaskStatus.UPLOADING, TaskStatus.S3_UPLOADED,
                    TaskTransitionEffect.S3_COMMITTED, "S3 PUT 成功且 DB 提交"),
            new TaskTransitionSpec("T9", TaskStatus.UPLOADING, TaskStatus.WAITING_RETRY,
                    TaskTransitionEffect.FAIL_BACKOFF, "S3 失败 / 超时 / 网络中断 / 认证失败"),
            new TaskTransitionSpec("T10", TaskStatus.UPLOADING, TaskStatus.READY,
                    TaskTransitionEffect.RELEASE, "崩溃恢复：lease 过期且 s3_uploaded_at IS NULL"),
            new TaskTransitionSpec("T11", TaskStatus.S3_UPLOADED, TaskStatus.GATEWAY_DELIVERING,
                    TaskTransitionEffect.NONE, "同次 worker 执行继续投递（保持 lease）"),
            new TaskTransitionSpec("T12", TaskStatus.S3_UPLOADED, TaskStatus.GATEWAY_DELIVERING,
                    TaskTransitionEffect.RELEASE, "崩溃恢复：lease 过期，释放所有权待认领续投"),
            new TaskTransitionSpec("T13", TaskStatus.GATEWAY_DELIVERING, TaskStatus.DELIVERED,
                    TaskTransitionEffect.COMPLETE, "Gateway 明确返回成功"),
            new TaskTransitionSpec("T14", TaskStatus.GATEWAY_DELIVERING, TaskStatus.WAITING_RETRY,
                    TaskTransitionEffect.FAIL_BACKOFF, "Gateway 明确失败（4xx/5xx）"),
            new TaskTransitionSpec("T15", TaskStatus.GATEWAY_DELIVERING, TaskStatus.WAITING_RETRY,
                    TaskTransitionEffect.FAIL_BACKOFF, "Gateway 超时 / 连接中断 / 结果未知"),
            new TaskTransitionSpec("T16", TaskStatus.GATEWAY_DELIVERING, TaskStatus.WAITING_RETRY,
                    TaskTransitionEffect.FAIL_BACKOFF, "崩溃恢复：lease 过期，不猜结果直接重投"),
            new TaskTransitionSpec("T17", TaskStatus.WAITING_RETRY, TaskStatus.GATEWAY_DELIVERING,
                    TaskTransitionEffect.CLAIM, "退避到期且 s3_uploaded_at IS NOT NULL → 重投"),
            new TaskTransitionSpec("T18", TaskStatus.WAITING_RETRY, TaskStatus.UPLOADING,
                    TaskTransitionEffect.CLAIM, "退避到期且 s3_uploaded_at IS NULL → 重传"),
            new TaskTransitionSpec("T19", TaskStatus.WAITING_RETRY, TaskStatus.READY,
                    TaskTransitionEffect.RELEASE, "manual retry（operator）"),
            new TaskTransitionSpec("T20", TaskStatus.WAITING_RETRY, TaskStatus.CANCELLED,
                    TaskTransitionEffect.CANCEL, "被更新稳定版本 supersede"),
            new TaskTransitionSpec("T21", TaskStatus.DISCOVERED, TaskStatus.CANCELLED,
                    TaskTransitionEffect.CANCEL, "supersede 统一入口"),
            new TaskTransitionSpec("T21", TaskStatus.STABILITY_CHECK, TaskStatus.CANCELLED,
                    TaskTransitionEffect.CANCEL, "supersede 统一入口"),
            new TaskTransitionSpec("T21", TaskStatus.READY, TaskStatus.CANCELLED,
                    TaskTransitionEffect.CANCEL, "supersede 统一入口"),
            new TaskTransitionSpec("T21", TaskStatus.WAITING_RETRY, TaskStatus.CANCELLED,
                    TaskTransitionEffect.CANCEL, "supersede 统一入口"));

    /** 校验并返回请求对应的迁移；不合法抛 {@link IllegalTransitionException}。 */
    public TaskTransitionSpec require(String code, TaskStatus from) {
        if (code == null) {
            throw new IllegalTransitionException("transition code must not be null", null, from, null);
        }
        return SPECS.stream()
                .filter(s -> s.code().equals(code) && java.util.Objects.equals(s.from(), from))
                .findFirst()
                .orElseThrow(() -> new IllegalTransitionException(
                        "illegal transition: code " + code + " has no allowed transition from " + from
                                + "; legal targets from " + from + " = " + legalTargets(from),
                        code, from, null));
    }

    /** 校验 (code, from, to) 三者一致；不合法抛 {@link IllegalTransitionException}。 */
    public TaskTransitionSpec require(String code, TaskStatus from, TaskStatus to) {
        TaskTransitionSpec spec = require(code, from);
        if (spec.to() != to) {
            throw new IllegalTransitionException(
                    "illegal transition: " + code + " from " + from + " goes to " + spec.to()
                            + ", not " + to,
                    code, from, to);
        }
        return spec;
    }

    /** 是否存在任意迁移可让 {@code from → to}（不指定 code，供状态对合法性断言）。 */
    public boolean isLegal(TaskStatus from, TaskStatus to) {
        return SPECS.stream()
                .anyMatch(s -> java.util.Objects.equals(s.from(), from) && s.to() == to);
    }

    /** 按状态对查找任一迁移；不存在抛 {@link IllegalTransitionException}。 */
    public TaskTransitionSpec requireByStates(TaskStatus from, TaskStatus to) {
        return SPECS.stream()
                .filter(s -> java.util.Objects.equals(s.from(), from) && s.to() == to)
                .findFirst()
                .orElseThrow(() -> new IllegalTransitionException(
                        "illegal transition between states: " + from + " -> " + to
                                + " is not in the authoritative matrix (04 §2)",
                        null, from, to));
    }

    /** 从某状态出发允许到达的状态集合（04 §2 迁移矩阵速查）。 */
    public Set<TaskStatus> legalTargets(TaskStatus from) {
        if (from == null) {
            return EnumSet.of(TaskStatus.DISCOVERED);
        }
        Set<TaskStatus> out = EnumSet.noneOf(TaskStatus.class);
        for (TaskTransitionSpec spec : SPECS) {
            if (spec.from() == from) {
                out.add(spec.to());
            }
        }
        return out;
    }
}
