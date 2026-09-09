package com.example.transfer.repository;

/**
 * 传输尝试结果（A4 裁决：UNKNOWN 表示结果未知，落库后按失败重投）。
 * 对应 transfer_attempt.outcome 的 CHECK。
 */
public enum AttemptOutcome {
    SUCCESS,
    FAILURE,
    UNKNOWN
}
