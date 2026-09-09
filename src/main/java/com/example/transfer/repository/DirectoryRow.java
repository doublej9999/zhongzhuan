package com.example.transfer.repository;

import java.time.OffsetDateTime;

/**
 * directory 表一行。
 * extensions 为 JSONB 的文本表示（如 {@code ["zip","xml"]}）；
 * lastScanAt 可空（对应 last_scan_at）。
 */
public record DirectoryRow(
        Long id,
        Long nasId,
        Long customerSpaceId,
        String code,
        String path,
        String extensions,
        long scanIntervalSec,
        int maxConcurrency,
        boolean enabled,
        OffsetDateTime lastScanAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
