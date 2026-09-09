package com.example.transfer.repository;

import java.time.OffsetDateTime;

/**
 * transfer_attempt 表一行（同一任务同一类型同一轮次唯一；
 * outcome=UNKNOWN 表达“结果未知可落库”，A4）。createdAt 由 DB 默认 now()。
 */
public record TransferAttemptRow(
        Long id,
        Long transferTaskId,
        AttemptType attemptType,
        int attemptNo,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        boolean success,
        AttemptOutcome outcome,
        Integer httpStatus,
        String errorCode,
        String errorMessage,
        String requestId,
        String gatewayRequestId,
        Long durationMs,
        OffsetDateTime createdAt) {
}
