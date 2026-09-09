package com.example.transfer.file;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * 扫描到的 NAS 文件元数据载体（不可变）。
 *
 * @param relativePath 相对 directory 的规范化路径（如 {@code order/2026/A.zip}，统一使用正斜杠）
 * @param sizeBytes    文件字节大小（必须 >= 0）
 * @param mtime        修改时间（TIMESTAMPTZ，统一时区为 UTC）
 */
public record FileMetadata(String relativePath, long sizeBytes, OffsetDateTime mtime) {

    public FileMetadata {
        Objects.requireNonNull(relativePath, "relativePath 必填");
        Objects.requireNonNull(mtime, "mtime 必填");
        if (relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath 不能为空白");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes 不能为负数: " + sizeBytes);
        }
        // 规范化路径分隔符为正斜杠，去除前导斜杠
        relativePath = normalizePath(relativePath);
        // 统一时区为 UTC，消除展示时区漂移
        mtime = mtime.withOffsetSameInstant(ZoneOffset.UTC);
    }

    public static FileMetadata of(String relativePath, long sizeBytes, Instant mtimeInstant) {
        Objects.requireNonNull(mtimeInstant, "mtimeInstant 必填");
        return new FileMetadata(relativePath, sizeBytes, mtimeInstant.atOffset(ZoneOffset.UTC));
    }

    public static FileMetadata of(String relativePath, long sizeBytes, OffsetDateTime mtime) {
        return new FileMetadata(relativePath, sizeBytes, mtime);
    }

    private static String normalizePath(String raw) {
        String p = raw.replace('\\', '/').trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }

    /** 物理时刻。 */
    public Instant mtimeInstant() {
        return mtime.toInstant();
    }
}
