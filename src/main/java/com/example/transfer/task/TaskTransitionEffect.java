package com.example.transfer.task;

/**
 * 迁移的落库副作用（除 status 与 updated_at 之外需要一并写的列）。
 *
 * <p>与 04-state-machine.md T1–T23 的效果一一对应：</p>
 * <ul>
 *   <li>{@link #CLAIM}：写 worker_id / claimed_at / lease_until（T6/T17/T18）</li>
 *   <li>{@link #RELEASE}：清空 worker_id/claimed_at/lease_until/next_retry_at（T10/T12/T19）</li>
 *   <li>{@link #S3_COMMITTED}：写 s3_uploaded_at（T8）</li>
 *   <li>{@link #FAIL_BACKOFF}：retry_count+1、next_retry_at、last_attempt_finished_at（T9/T14/T15/T16）</li>
 *   <li>{@link #COMPLETE}：写 completed_at（T13，含 RELEASE）</li>
 *   <li>{@link #CANCEL}：写 cancelled_at/cancel_reason/superseded_by_version_no（T5/T7/T20/T21，含 RELEASE）</li>
 *   <li>{@link #STABLE_FILE}：写 file_version.stable_at（T4，另一张表，同事务）</li>
 * </ul>
 */
public enum TaskTransitionEffect {
    NONE,
    CLAIM,
    RELEASE,
    S3_COMMITTED,
    FAIL_BACKOFF,
    COMPLETE,
    CANCEL,
    STABLE_FILE
}
