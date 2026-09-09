package com.example.transfer.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * S3KeyGenerator 单测：严格遵循 BR-35 与整体设计 §40。
 */
class S3KeyGeneratorTest {

    @Test
    void standardKeyGeneration() {
        String key = S3KeyGenerator.generate("customer-a", "order/2026/09/A.zip");
        assertEquals("customer-a/order/2026/09/A.zip", key);
    }

    @Test
    void slashNormalization() {
        // 前导斜杠、反斜杠、多余斜杠必须规范化
        String key = S3KeyGenerator.generate("/customer-b/", "\\invoice\\B.csv");
        assertEquals("customer-b/invoice/B.csv", key);
    }

    @Test
    void strictlyForbidDisallowedTokens() {
        String key = S3KeyGenerator.generate("cust-space", "sub/data.xml");
        // 绝对不能含随机与版本字段
        assertFalse(key.contains("uuid"));
        assertFalse(key.contains("timestamp"));
        assertFalse(key.contains("taskId"));
        assertFalse(key.contains("v1"));
        assertFalse(key.contains("version"));
    }

    @Test
    void emptyInputRejected() {
        assertThrows(IllegalArgumentException.class, () -> S3KeyGenerator.generate("", "a.zip"));
        assertThrows(IllegalArgumentException.class, () -> S3KeyGenerator.generate("cs", ""));
        assertThrows(NullPointerException.class, () -> S3KeyGenerator.generate(null, "a.zip"));
        assertThrows(NullPointerException.class, () -> S3KeyGenerator.generate("cs", null));
    }
}
