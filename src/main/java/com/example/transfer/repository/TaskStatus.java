package com.example.transfer.repository;

/**
 * 传输任务 9 态状态机（与 V1 DDL ck_transfer_task_status 完全一致；
 * 无 FAILED/CLAIMED/SUPERSEDED，失败走 WAITING_RETRY，取消走 CANCELLED）。
 */
public enum TaskStatus {
    DISCOVERED,
    STABILITY_CHECK,
    READY,
    UPLOADING,
    S3_UPLOADED,
    GATEWAY_DELIVERING,
    WAITING_RETRY,
    DELIVERED,
    CANCELLED
}
