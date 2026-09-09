package com.example.transfer.task;

import java.time.OffsetDateTime;

import com.example.transfer.repository.TaskStatus;

/**
 * 一次迁移请求。除 taskId / code / expectedFrom 外全部可选，按迁移副作用选择性提供
 * （如 T6/T17/T18 的 claim 需要 workerId 与 leaseUntil）。
 *
 * @param attempt 可选：与状态迁移同一事务落库的 transfer_attempt（Phase 7/8 Worker 使用）
 */
public record TransitionRequest(
        long taskId,
        String code,
        TaskStatus expectedFrom,
        String workerId,
        OffsetDateTime leaseUntil,
        OffsetDateTime s3UploadedAt,
        OffsetDateTime nextRetryAt,
        String cancelReason,
        Integer supersededByVersionNo,
        OffsetDateTime fileVersionStableAt,
        String operator,
        String reason,
        AttemptWrite attempt) {

    /** claim 类迁移（T6/T17/T18）：写 worker 与 lease。 */
    public static TransitionRequest claim(long taskId, String code, TaskStatus from,
            String workerId, OffsetDateTime leaseUntil) {
        return new TransitionRequest(taskId, code, from, workerId, leaseUntil, null, null,
                null, null, null, null, null, null);
    }

    /** S3 提交成功（T8）。 */
    public static TransitionRequest s3Committed(long taskId, OffsetDateTime s3UploadedAt) {
        return new TransitionRequest(taskId, "T8", TaskStatus.UPLOADING, null, null,
                s3UploadedAt, null, null, null, null, null, null, null);
    }

    /** 失败/未知结果进入退避（T9/T14/T15/T16），backoff 到期时间必填。 */
    public static TransitionRequest failure(long taskId, String code, TaskStatus from,
            OffsetDateTime nextRetryAt, AttemptWrite attempt) {
        return new TransitionRequest(taskId, code, from, null, null, null, nextRetryAt,
                null, null, null, null, null, attempt);
    }

    /** 成功完成（T13），Gateway 成功 attempt 必填。 */
    public static TransitionRequest success(long taskId, TaskStatus from, AttemptWrite attempt) {
        return new TransitionRequest(taskId, "T13", from, null, null, null, null,
                null, null, null, null, null, attempt);
    }

    /** 取消类迁移（T5/T7/T20/T21）。 */
    public static TransitionRequest cancel(long taskId, String code, TaskStatus from,
            String cancelReason, Integer supersededByVersionNo) {
        return new TransitionRequest(taskId, code, from, null, null, null, null,
                cancelReason, supersededByVersionNo, null, null, null, null);
    }

    /** manual retry（T19）：最新稳定版本由 operator 强制回 READY。 */
    public static TransitionRequest manualRetry(long taskId, String operator) {
        return new TransitionRequest(taskId, "T19", TaskStatus.WAITING_RETRY, null, null, null,
                null, null, null, null, operator, null, null);
    }

    /** 崩溃恢复（T10/T12/T16）：按当前状态释放 lease 后迁移。 */
    public static TransitionRequest recovery(long taskId, String code, TaskStatus from) {
        return new TransitionRequest(taskId, code, from, null, null, null, null,
                null, null, null, null, null, null);
    }
}
