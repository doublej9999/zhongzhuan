package com.example.transfer.scanner;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 扫描目录的目标上下文描述。
 *
 * @param directoryId       数据库 directory 主键
 * @param nasId             数据库 nas 主键
 * @param customerSpaceId   数据库 customer_space 主键
 * @param directoryCode     目录业务代号（如 {@code customer-a-order}）
 * @param customerSpaceCode 客户空间代号（如 {@code customer-a}，用于 S3 Key）
 * @param rootPath          物理根目录绝对路径（如挂载点下的实际路径）
 * @param extensions        后缀白名单（如 {@code [".zip", ".csv"]} 或 {@code ["zip", "csv"]}）
 * @param scanIntervalSec   扫描周期秒数
 * @param enabled           是否启用
 */
public record DirectoryScanTarget(
        long directoryId,
        long nasId,
        long customerSpaceId,
        String directoryCode,
        String customerSpaceCode,
        Path rootPath,
        List<String> extensions,
        long scanIntervalSec,
        boolean enabled) {

    public DirectoryScanTarget {
        Objects.requireNonNull(directoryCode, "directoryCode 必填");
        Objects.requireNonNull(customerSpaceCode, "customerSpaceCode 必填");
        Objects.requireNonNull(rootPath, "rootPath 必填");
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
    }

    /**
     * 判断给定文件名是否匹配后缀白名单（大小写不敏感）。若未配置白名单则默认允许所有文件。
     */
    public boolean matchesExtension(String fileName) {
        if (extensions.isEmpty()) {
            return true;
        }
        String lowerName = fileName.toLowerCase();
        for (String ext : extensions) {
            String clean = ext.trim().toLowerCase();
            if (!clean.startsWith(".")) {
                clean = "." + clean;
            }
            if (lowerName.endsWith(clean)) {
                return true;
            }
        }
        return false;
    }
}
