package com.example.transfer.task;

import com.example.transfer.repository.TaskStatus;

/**
 * 一条合法迁移规格：{@code (code, from, to)} 唯一确定一条迁移（如 T11 与 T12 同为
 * S3_UPLOADED→GATEWAY_DELIVERING 但 code 不同、副作用不同）。
 *
 * @param code        迁移编号，如 "T6"（04 §2 权威矩阵）
 * @param from        起始状态；T1（初始创建）为 null
 * @param to          目标状态
 * @param effect      落库副作用
 * @param description 语义说明
 */
public record TaskTransitionSpec(
        String code,
        TaskStatus from,
        TaskStatus to,
        TaskTransitionEffect effect,
        String description) {
}
