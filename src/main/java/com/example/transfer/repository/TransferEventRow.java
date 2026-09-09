package com.example.transfer.repository;

import java.time.OffsetDateTime;

/**
 * transfer_event 表一行（可选审计：状态迁移 / operator 动作留痕）。
 * detail 为 JSONB 的文本表示且可空；fromStatus/reason 可空；createdAt 由 DB 默认 now()。
 */
public record TransferEventRow(
        Long id,
        Long transferTaskId,
        String fromStatus,
        String toStatus,
        String reason,
        String operator,
        String detail,
        OffsetDateTime createdAt) {
}
