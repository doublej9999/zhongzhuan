package com.example.transfer.storage;

import java.util.Objects;

/**
 * S3 Object Key 生成器：
 *
 * <p><strong>核心约束（BR-35、提示词 §十一、整体设计 §40）</strong>：
 * <ul>
 *   <li>格式必须为：{@code <customer-space>/<relative-path>}；</li>
 *   <li><strong>严格禁止</strong>包含 UUID、timestamp、taskId、version_no、fingerprint 或任何随机后缀；</li>
 *   <li>新版本直接覆盖同一 Key，这是同文件串行与覆盖语义（§77）的前提。</li>
 * </ul>
 */
public final class S3KeyGenerator {

    private S3KeyGenerator() {
    }

    /**
     * 生成规范的 S3 Object Key。
     *
     * @param customerSpaceCode 客户空间标识（如 {@code customer-a}）
     * @param relativePath      文件相对路径（如 {@code order/2026/09/A.zip}）
     * @return 格式为 {@code <customer-space>/<relative-path>} 的 Key
     */
    public static String generate(String customerSpaceCode, String relativePath) {
        Objects.requireNonNull(customerSpaceCode, "customerSpaceCode 必填");
        Objects.requireNonNull(relativePath, "relativePath 必填");

        String space = customerSpaceCode.trim();
        if (space.isEmpty()) {
            throw new IllegalArgumentException("customerSpaceCode 不能为空");
        }

        String path = relativePath.replace('\\', '/').trim();
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty()) {
            throw new IllegalArgumentException("relativePath 不能为空");
        }

        while (space.endsWith("/")) {
            space = space.substring(0, space.length() - 1);
        }
        while (space.startsWith("/")) {
            space = space.substring(1);
        }

        return space + "/" + path;
    }
}
