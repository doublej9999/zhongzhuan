package com.example.transfer.repository;

import java.time.OffsetDateTime;

/**
 * transfer_task 表一行（9 态状态机；file_id/version_no 为 A3 冗余列）。
 * 除 status/priority/retry_count/version_no 外大量可空时间/游标列用对象类型表达。
 */
public record TransferTaskRow(
        Long id,
        Long fileVersionId,
        Long fileId,
        int versionNo,
        Long directoryId,
        Long customerSpaceId,
        String filePath,
        long fileSizeBytes,
        OffsetDateTime fileMtime,
        String s3Bucket,
        String s3ObjectKey,
        OffsetDateTime s3UploadedAt,
        String gatewayRoute,
        String configVersion,
        TaskStatus status,
        int priority,
        int retryCount,
        OffsetDateTime nextRetryAt,
        String workerId,
        OffsetDateTime claimedAt,
        OffsetDateTime leaseUntil,
        OffsetDateTime lastAttemptFinishedAt,
        String cancelReason,
        Integer supersededByVersionNo,
        OffsetDateTime cancelledAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
