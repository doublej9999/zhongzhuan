package com.example.transfer.repository;

/** 传输尝试类型（对应 transfer_attempt.attempt_type 的 CHECK）。 */
public enum AttemptType {
    S3_UPLOAD,
    GATEWAY_DELIVER
}
