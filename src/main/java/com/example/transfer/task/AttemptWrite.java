package com.example.transfer.task;

import java.time.OffsetDateTime;

import com.example.transfer.repository.AttemptOutcome;
import com.example.transfer.repository.AttemptType;

/**
 * 待与状态迁移同一事务落库的一次 transfer_attempt（04 §3 约束 3：迁移 + attempt + event
 * 同一事务）。attempt_no 由调用方（Worker）计算，避免与现有行冲突。
 */
public record AttemptWrite(
        AttemptType attemptType,
        int attemptNo,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        boolean success,
        AttemptOutcome outcome,
        Integer httpStatus,
        String errorCode,
        String errorMessage,
        String gatewayRequestId,
        Long durationMs) {

    public AttemptWrite {
        if (attemptType == null || outcome == null) {
            throw new IllegalArgumentException("attemptType/outcome 必填");
        }
        if (attemptNo < 1) {
            throw new IllegalArgumentException("attemptNo 必须 >= 1");
        }
    }
}
