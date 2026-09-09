/**
 * worker 模块：任务认领（FOR UPDATE SKIP LOCKED）、lease 续租与执行并发控制。
 *
 * <p>并发与背压规则见 {@code docs/phase-0/08-concurrency-model.md}；Phase 6 实现。</p>
 */
package com.example.transfer.worker;
