package com.example.transfer.file;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 文件指纹计算工具：
 *
 * <p><strong>核心约束（BR-15、Q2 默认口径）</strong>：
 * <ul>
 *   <li><strong>绝不读取文件内容</strong>，不计算文件内容 SHA-256；</li>
 *   <li>指纹仅由元数据推导：{@code SHA-256(relative_path + "|" + size + "|" + mtime_epoch_nanos)}；</li>
 *   <li>时间统一采用 UTC epoch 纳秒（{@code epochSecond * 1_000_000_000 + nanoOfSecond}），
 *       确保跨 JVM、跨时区计算完全确定。</li>
 * </ul>
 */
public final class FileFingerprint {

    private static final HexFormat HEX = HexFormat.of();

    private FileFingerprint() {
    }

    /**
     * 计算元数据指纹（64 位小写十六进制 SHA-256 串）。
     */
    public static String compute(FileMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata 必填");
        return compute(metadata.relativePath(), metadata.sizeBytes(), metadata.mtimeInstant());
    }

    /**
     * 底层计算方法：{@code relativePath + "|" + sizeBytes + "|" + epochNanos}。
     */
    public static String compute(String relativePath, long sizeBytes, Instant mtime) {
        Objects.requireNonNull(relativePath, "relativePath 必填");
        Objects.requireNonNull(mtime, "mtime 必填");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes 不能为负数: " + sizeBytes);
        }

        long epochNanos = Math.multiplyExact(mtime.getEpochSecond(), 1_000_000_000L)
                + mtime.getNano();

        String raw = relativePath + "|" + sizeBytes + "|" + epochNanos;
        return sha256Hex(raw);
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
