package com.example.transfer.repository;

import java.time.OffsetDateTime;

/**
 * nas 表一行（不可变；enabled 为数据库 BOOLEAN NOT NULL，用基本类型）。
 * 时间列 TIMESTAMPTZ → {@link OffsetDateTime}。
 */
public record NasRow(
        Long id,
        String name,
        String mountPath,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
