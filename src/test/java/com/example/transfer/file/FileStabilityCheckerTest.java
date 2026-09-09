package com.example.transfer.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * FileStabilityChecker 覆盖全部 6 个核心场景：
 *
 * <ol>
 *   <li>第一次看到（第一次观察基线，未稳定）</li>
 *   <li>第二次相同（连续两次相同 → STABLE）</li>
 *   <li>size 改变（写入中 → MODIFIED，重置基线）</li>
 *   <li>mtime 改变（覆盖写入 → MODIFIED，重置基线）</li>
 *   <li>文件消失（VANISHED，准备触发 T5 取消）</li>
 *   <li>文件重新出现（REAPPEARED，重置稳定检测）</li>
 * </ol>
 */
class FileStabilityCheckerTest {

    private final FileStabilityChecker checker = new FileStabilityChecker();

    private static final Instant T0 = Instant.parse("2026-09-09T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-09T10:05:00Z");

    // 场景 1：第一次看到
    @Test
    void scenario1_firstSeen() {
        FileMetadata meta = FileMetadata.of("order/A.zip", 1024L, T0);
        StabilityAssessment result = checker.assess(null, meta);

        assertEquals(StabilityStatus.FIRST_SEEN, result.status());
        assertFalse(result.isStable());
        assertNotNull(result.currentFingerprint());
        assertEquals(meta, result.currentMetadata());
        assertNull(result.previousSnapshot());
    }

    // 场景 2：第二次相同 → 判定稳定
    @Test
    void scenario2_secondIdenticalObservationIsStable() {
        FileMetadata firstMeta = FileMetadata.of("order/A.zip", 1024L, T0);
        String fp = FileFingerprint.compute(firstMeta);
        FileObservation firstObs = FileObservation.of(firstMeta, fp, T0.atOffset(ZoneOffset.UTC));

        // 第二次扫描：path, size, mtime 完全一致
        FileMetadata secondMeta = FileMetadata.of("order/A.zip", 1024L, T0);
        StabilityAssessment result = checker.assess(firstObs, secondMeta);

        assertEquals(StabilityStatus.STABLE, result.status());
        assertTrue(result.isStable());
        assertEquals(fp, result.currentFingerprint());
        assertEquals(firstObs, result.previousSnapshot());
    }

    // 场景 3：size 改变（文件正在追加写入）
    @Test
    void scenario3_sizeChangedResetsBaseline() {
        FileMetadata firstMeta = FileMetadata.of("order/A.zip", 1024L, T0);
        String fp1 = FileFingerprint.compute(firstMeta);
        FileObservation firstObs = FileObservation.of(firstMeta, fp1, T0.atOffset(ZoneOffset.UTC));

        // 第二次扫描：size 从 1024 增加到 2048，mtime 不变
        FileMetadata secondMeta = FileMetadata.of("order/A.zip", 2048L, T0);
        StabilityAssessment result = checker.assess(firstObs, secondMeta);

        assertEquals(StabilityStatus.MODIFIED, result.status());
        assertFalse(result.isStable());
        assertTrue(result.reason().contains("size 发生变化"));
    }

    // 场景 4：mtime 改变（同一大小被覆盖）
    @Test
    void scenario4_mtimeChangedResetsBaseline() {
        FileMetadata firstMeta = FileMetadata.of("order/A.zip", 1024L, T0);
        String fp1 = FileFingerprint.compute(firstMeta);
        FileObservation firstObs = FileObservation.of(firstMeta, fp1, T0.atOffset(ZoneOffset.UTC));

        // 第二次扫描：size 相同但 mtime 推进了 5 分钟
        FileMetadata secondMeta = FileMetadata.of("order/A.zip", 1024L, T1);
        StabilityAssessment result = checker.assess(firstObs, secondMeta);

        assertEquals(StabilityStatus.MODIFIED, result.status());
        assertFalse(result.isStable());
        assertTrue(result.reason().contains("mtime 推进"));
    }

    // 场景 5：文件消失（current 为 null）
    @Test
    void scenario5_fileVanished() {
        FileMetadata firstMeta = FileMetadata.of("order/A.zip", 1024L, T0);
        String fp = FileFingerprint.compute(firstMeta);
        FileObservation firstObs = FileObservation.of(firstMeta, fp, T0.atOffset(ZoneOffset.UTC));

        // 第二次扫描：文件从目录中移除
        StabilityAssessment result = checker.assess(firstObs, null);

        assertEquals(StabilityStatus.VANISHED, result.status());
        assertFalse(result.isStable());
        assertNull(result.currentMetadata());
        assertNull(result.currentFingerprint());
        assertEquals(firstObs, result.previousSnapshot());
        assertTrue(result.reason().contains("消失"));
    }

    // 场景 6：文件重新出现
    @Test
    void scenario6_fileReappeared() {
        FileMetadata meta = FileMetadata.of("order/A.zip", 1024L, T0);
        String fp = FileFingerprint.compute(meta);
        OffsetDateTime t0Offset = T0.atOffset(ZoneOffset.UTC);
        FileObservation vanishedObs = FileObservation.of(meta, fp, t0Offset).markVanished(t0Offset.plusMinutes(10));

        // 第三次扫描：同名文件重新被发现
        FileMetadata newMeta = FileMetadata.of("order/A.zip", 2048L, T1);
        StabilityAssessment result = checker.assess(vanishedObs, newMeta);

        assertEquals(StabilityStatus.REAPPEARED, result.status());
        assertFalse(result.isStable());
        assertTrue(result.reason().contains("重新出现"));
    }

    @Test
    void invalidInputRejected() {
        assertThrows(IllegalArgumentException.class, () -> checker.assess(null, null));
    }
}
