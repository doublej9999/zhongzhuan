package com.example.transfer.repository;

import java.time.OffsetDateTime;

/** file 表一行（逻辑文件，同一 (nas,directory,relative_path) 唯一）。 */
public record FileRow(
        Long id,
        Long nasId,
        Long directoryId,
        String relativePath,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
