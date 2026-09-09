/**
 * retry 模块：指数退避重试调度（maxDelay 上界；失败永不放弃，无 FAILED 终态）。
 *
 * <p>对应 {@code docs/phase-0/01-functional.md} FR-07；Phase 9 实现。</p>
 */
package com.example.transfer.retry;
