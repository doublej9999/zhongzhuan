package com.example.transfer.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * FileFingerprint 纯逻辑单测：
 *
 * <ul>
 *   <li>相同元数据生成完全相同指纹</li>
 *   <li>path / size / mtime 任一变动必导致指纹不同</li>
 *   <li>跨时区但同一物理时刻（同一 Instant）指纹必须完全一致</li>
 *   <li>输出必须为 64 位十六进制小写 SHA-256 串</li>
 * </ul>
 */
class FileFingerprintTest {

    private static final Instant T0 = Instant.parse("2026-09-09T10:00:00.123456789Z");

    @Test
    void identicalMetadataProducesIdenticalFingerprint() {
        FileMetadata a = FileMetadata.of("order/A.zip", 1024L, T0);
        FileMetadata b = FileMetadata.of("order/A.zip", 1024L, T0);

        assertEquals(FileFingerprint.compute(a), FileFingerprint.compute(b));
    }

    @Test
    void differentPathProducesDifferentFingerprint() {
        String fp1 = FileFingerprint.compute("order/A.zip", 1024L, T0);
        String fp2 = FileFingerprint.compute("order/B.zip", 1024L, T0);

        assertNotEquals(fp1, fp2);
    }

    @Test
    void differentSizeProducesDifferentFingerprint() {
        String fp1 = FileFingerprint.compute("order/A.zip", 1024L, T0);
        String fp2 = FileFingerprint.compute("order/A.zip", 1025L, T0);

        assertNotEquals(fp1, fp2);
    }

    @Test
    void differentMtimeProducesDifferentFingerprint() {
        String fp1 = FileFingerprint.compute("order/A.zip", 1024L, T0);
        String fp2 = FileFingerprint.compute("order/A.zip", 1024L, T0.plusNanos(1));

        assertNotEquals(fp1, fp2);
    }

    @Test
    void timezoneInvariantForSameInstant() {
        // 同一时刻：UTC vs +08:00
        OffsetDateTime utc = T0.atOffset(ZoneOffset.UTC);
        OffsetDateTime beijing = T0.atOffset(ZoneOffset.ofHours(8));

        FileMetadata a = FileMetadata.of("order/A.zip", 1024L, utc);
        FileMetadata b = FileMetadata.of("order/A.zip", 1024L, beijing);

        assertEquals(FileFingerprint.compute(a), FileFingerprint.compute(b));
    }

    @Test
    void pathNormalizationRemovesLeadingSlashesAndBackslashes() {
        FileMetadata a = FileMetadata.of("order/A.zip", 1024L, T0);
        FileMetadata b = FileMetadata.of("/order/A.zip", 1024L, T0);
        FileMetadata c = FileMetadata.of("order\\A.zip", 1024L, T0);

        assertEquals(FileFingerprint.compute(a), FileFingerprint.compute(b));
        assertEquals(FileFingerprint.compute(a), FileFingerprint.compute(c));
    }

    @Test
    void formatIs64HexLowercase() {
        String fp = FileFingerprint.compute("order/A.zip", 1024L, T0);
        assertEquals(64, fp.length());
        assertTrue(fp.matches("^[0-9a-f]{64}$"));
    }

    @Test
    void negativeSizeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> FileFingerprint.compute("order/A.zip", -1L, T0));
    }
}
