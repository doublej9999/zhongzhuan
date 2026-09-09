package com.example.transfer.file;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 历史单次观察记录（可映射自 file_version 或内存扫描快照）。
 *
 * @param relativePath 相对路径
 * @param sizeBytes    字节大小
 * @param mtime        文件 mtime
 * @param fingerprint  根据元数据计算的指纹
 * @param observedAt   本次观察时刻
 * @param vanished     该历史记录是否标记为已消失
 */
public record FileObservation(
        String relativePath,
        long sizeBytes,
        OffsetDateTime mtime,
        String fingerprint,
        OffsetDateTime observedAt,
        boolean vanished) {

    public FileObservation {
        Objects.requireNonNull(relativePath, "relativePath 必填");
        Objects.requireNonNull(mtime, "mtime 必填");
        Objects.requireNonNull(fingerprint, "fingerprint 必填");
        Objects.requireNonNull(observedAt, "observedAt 必填");
    }

    /** 基于元数据与指纹构造正常观察记录。 */
    public static FileObservation of(FileMetadata metadata, String fingerprint, OffsetDateTime observedAt) {
        return new FileObservation(
                metadata.relativePath(),
                metadata.sizeBytes(),
                metadata.mtime(),
                fingerprint,
                observedAt,
                false);
    }

    /** 构造一条标记为已消失的观察快照。 */
    public FileObservation markVanished(OffsetDateTime at) {
        return new FileObservation(relativePath, sizeBytes, mtime, fingerprint, at, true);
    }
}
