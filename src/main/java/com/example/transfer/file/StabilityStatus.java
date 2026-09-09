package com.example.transfer.file;

/**
 * 文件稳定性检测结果：
 */
public enum StabilityStatus {
    /** 首次发现该文件，建立第一次观察基线，尚未稳定。对应状态机 T1/T2 进入 STABILITY_CHECK。 */
    FIRST_SEEN,

    /** 连续两次观察的 path + size + mtime 完全一致，判定为稳定。对应状态机 T4 进入 READY。 */
    STABLE,

    /** 文件发生变化（size 或 mtime 改变），文件仍在写入或被覆盖，重置稳定基线。对应状态机 T3 自迁移。 */
    MODIFIED,

    /** 稳定前文件从目录中消失。对应状态机 T5 取消。 */
    VANISHED,

    /** 曾经消失的文件重新出现，重新建立第一次观察基线。 */
    REAPPEARED
}
