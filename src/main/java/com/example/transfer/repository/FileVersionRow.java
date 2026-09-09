package com.example.transfer.repository;

import java.time.OffsetDateTime;

/**
 * file_version 表一行。
 * stableAt 可空（稳定语义 = stable_at IS NOT NULL，A5）；firstSeenAt 由 DB 默认 now()。
 */
public record FileVersionRow(
        Long id,
        Long fileId,
        int versionNo,
        long sizeBytes,
        OffsetDateTime mtime,
        String fingerprint,
        OffsetDateTime firstSeenAt,
        OffsetDateTime stableAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
