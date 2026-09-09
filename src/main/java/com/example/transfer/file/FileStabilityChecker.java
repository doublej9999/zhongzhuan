package com.example.transfer.file;

import org.springframework.stereotype.Component;

/**
 * 文件稳定性检测核心（无状态纯组件）：
 *
 * <p><strong>核心原则（BR-14、04 §2 T4）</strong>：
 * 只有相邻两次扫描的 {@code relative_path + size + mtime} 完全一致（即指纹相同），
 * 且前一次未被标记为已消失，才判定为 {@link StabilityStatus#STABLE}。
 *
 * <p>覆盖全部 6 场景：
 * <ol>
 *   <li>第一次看到（previous == null）→ {@link StabilityStatus#FIRST_SEEN}</li>
 *   <li>第二次相同（指纹完全一致）→ {@link StabilityStatus#STABLE}</li>
 *   <li>size 改变 → {@link StabilityStatus#MODIFIED}</li>
 *   <li>mtime 改变 → {@link StabilityStatus#MODIFIED}</li>
 *   <li>文件消失（current == null 且 previous 存在）→ {@link StabilityStatus#VANISHED}</li>
 *   <li>重新出现（previous 标记为 vanished 且当前重新出现）→ {@link StabilityStatus#REAPPEARED}</li>
 * </ol>
 */
@Component
public class FileStabilityChecker {

    /**
     * 评估一次文件观察结果。
     *
     * @param previous 前一次观察快照（若是新文件则传 null）
     * @param current  当前扫描到的元数据（若当前扫描未列出该文件则传 null 表示消失）
     * @return 稳定判定结果
     */
    public StabilityAssessment assess(FileObservation previous, FileMetadata current) {
        // 场景 5：文件消失
        if (current == null) {
            if (previous == null || previous.vanished()) {
                throw new IllegalArgumentException("当前与前次观察不能同时为空/消失");
            }
            return new StabilityAssessment(
                    StabilityStatus.VANISHED,
                    null,
                    null,
                    previous,
                    "文件在 NAS 目录中消失（前次指纹: " + previous.fingerprint() + "）");
        }

        String currentFp = FileFingerprint.compute(current);

        // 场景 1：第一次看到
        if (previous == null) {
            return new StabilityAssessment(
                    StabilityStatus.FIRST_SEEN,
                    current,
                    currentFp,
                    null,
                    "首次发现文件，建立稳定检测基线");
        }

        // 场景 6：文件曾经消失，现重新出现
        if (previous.vanished()) {
            return new StabilityAssessment(
                    StabilityStatus.REAPPEARED,
                    current,
                    currentFp,
                    previous,
                    "文件此前已消失，本次重新出现，重置稳定检测基线");
        }

        // 场景 2：连续两次完全一致 → STABLE
        if (currentFp.equals(previous.fingerprint())) {
            return new StabilityAssessment(
                    StabilityStatus.STABLE,
                    current,
                    currentFp,
                    previous,
                    "连续两次观察 path + size + mtime 完全一致，判定为稳定");
        }

        // 场景 3 & 4：size 或 mtime 改变 → MODIFIED
        String diffReason;
        if (current.sizeBytes() != previous.sizeBytes()
                && !current.mtimeInstant().equals(previous.mtime().toInstant())) {
            diffReason = "size 发生变化 (" + previous.sizeBytes() + " -> " + current.sizeBytes()
                    + ") 且 mtime 推进 (" + previous.mtime() + " -> " + current.mtime() + ")";
        } else if (current.sizeBytes() != previous.sizeBytes()) {
            diffReason = "size 发生变化 (" + previous.sizeBytes() + " -> " + current.sizeBytes() + ")";
        } else {
            diffReason = "mtime 推进 (" + previous.mtime() + " -> " + current.mtime() + ")";
        }

        return new StabilityAssessment(
                StabilityStatus.MODIFIED,
                current,
                currentFp,
                previous,
                "文件仍在写入或被覆盖 (" + diffReason + ")，重置稳定检测基线");
    }
}
