package com.example.transfer.scanner;

/**
 * 单次扫描执行结果汇总。
 *
 * @param discoveredCount 新发现文件数（转入 DISCOVERED -> STABILITY_CHECK）
 * @param stableCount     经对比确认稳定的文件数（转入 READY）
 * @param modifiedCount   检测到写入中或覆盖修改的文件数（保持 STABILITY_CHECK）
 * @param vanishedCount   稳定前从目录消失的文件数（转入 CANCELLED）
 * @param unchangedCount  已处于稳定或后续流程且未变动跳过的文件数
 * @param newVersionCount 文件在上一版本处理后发生新变更而产生的新版本数
 * @param success         扫描是否成功完成
 * @param errorMessage    失败时的简要错误信息
 */
public record ScanResult(
        int discoveredCount,
        int stableCount,
        int modifiedCount,
        int vanishedCount,
        int unchangedCount,
        int newVersionCount,
        boolean success,
        String errorMessage) {

    public static ScanResult success(int discovered, int stable, int modified, int vanished,
            int unchanged, int newVersion) {
        return new ScanResult(discovered, stable, modified, vanished, unchanged, newVersion, true, null);
    }

    public static ScanResult failure(String errorMessage) {
        return new ScanResult(0, 0, 0, 0, 0, 0, false, errorMessage);
    }
}
