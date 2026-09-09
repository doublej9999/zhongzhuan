package com.example.transfer.file;

import java.util.Objects;

/**
 * 稳定检测评估结果详情。
 *
 * @param status             判定状态
 * @param currentMetadata    当前观察到的元数据（文件消失时为 null）
 * @param currentFingerprint 当前计算出的指纹（文件消失时为 null）
 * @param previousSnapshot   前一次观察快照（首次看到时为 null）
 * @param reason             判定原因说明
 */
public record StabilityAssessment(
        StabilityStatus status,
        FileMetadata currentMetadata,
        String currentFingerprint,
        FileObservation previousSnapshot,
        String reason) {

    public StabilityAssessment {
        Objects.requireNonNull(status, "status 必填");
        Objects.requireNonNull(reason, "reason 必填");
    }

    public boolean isStable() {
        return status == StabilityStatus.STABLE;
    }
}
