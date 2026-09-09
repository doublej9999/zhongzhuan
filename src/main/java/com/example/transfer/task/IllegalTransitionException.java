package com.example.transfer.task;

import com.example.transfer.repository.TaskStatus;

/**
 * 非法状态迁移：请求的迁移（code/from/to）不在合法迁移集合中，或终态被再次迁移。
 *
 * <p>抛出后状态不得改变（04 §3：拒绝 + 审计 + 无状态污染）。</p>
 */
public class IllegalTransitionException extends RuntimeException {

    private final String code;
    private final TaskStatus from;
    private final TaskStatus to;

    public IllegalTransitionException(String message, String code, TaskStatus from, TaskStatus to) {
        super(message);
        this.code = code;
        this.from = from;
        this.to = to;
    }

    public String getCode() {
        return code;
    }

    public TaskStatus getFrom() {
        return from;
    }

    public TaskStatus getTo() {
        return to;
    }
}
