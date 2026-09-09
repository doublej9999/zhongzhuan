package com.example.transfer.task;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.transfer.repository.TaskStatus;
import com.example.transfer.repository.TransferAttemptRepository;
import com.example.transfer.repository.TransferAttemptRow;
import com.example.transfer.repository.TransferEventRepository;
import com.example.transfer.repository.TransferEventRow;

/**
 * 状态迁移服务（04 §3 的并发与事务约束唯一实现入口）：
 *
 * <ol>
 *   <li>唯一入口：业务代码禁止直接改 status，只允许经本服务迁移；</li>
 *   <li>条件更新（乐观并发）：{@code UPDATE ... WHERE id=:id AND status=:from}，影响行数 0
 *       → {@link ConcurrentTransitionException}（放弃本次动作，由下一轮调度重判）；</li>
 *   <li>事务边界：状态迁移 + 可选 transfer_attempt + transfer_event 在同一事务内提交；</li>
 *   <li>外部 IO（S3/Gateway HTTP）必须由调用方在事务外执行，只把结果带回本方法。</li>
 * </ol>
 */
@Service
public class TaskTransitionService {

    private final TaskStateMachine stateMachine;
    private final NamedParameterJdbcTemplate named;
    private final TransferAttemptRepository attemptRepository;
    private final TransferEventRepository eventRepository;

    public TaskTransitionService(TaskStateMachine stateMachine,
            NamedParameterJdbcTemplate named,
            TransferAttemptRepository attemptRepository,
            TransferEventRepository eventRepository) {
        this.stateMachine = stateMachine;
        this.named = named;
        this.attemptRepository = attemptRepository;
        this.eventRepository = eventRepository;
    }

    /**
     * 执行一次状态迁移（同一事务：条件更新 + 副作用 + 可选 attempt + transfer_event 审计）。
     *
     * @throws IllegalTransitionException     迁移不在合法集合（状态不变）
     * @throws ConcurrentTransitionException 条件更新命中 0 行（放弃本次动作）
     */
    @Transactional
    public void transition(TransitionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        TaskStatus from = request.expectedFrom();
        TaskTransitionSpec spec = stateMachine.require(request.code(), from);
        TaskStatus to = spec.to();

        // T1 是"初始创建"（走 repository.insert），不是可执行的状态迁移
        if (spec.from() == null) {
            throw new IllegalTransitionException(
                    "T1 is an initial creation, use TransferTaskRepository.insert(...)", "T1", from, to);
        }

        MapSqlParameterSource params = new MapSqlParameterSource();
        List<String> sets = new ArrayList<>();
        sets.add("status = :toStatus");
        params.addValue("toStatus", to.name());
        sets.add("updated_at = now()");
        applyEffect(spec.effect(), request, params, sets);

        params.addValue("id", request.taskId());
        params.addValue("fromStatus", from.name());
        String sql = "UPDATE transfer_task SET " + String.join(", ", sets)
                + " WHERE id = :id AND status = :fromStatus";
        int updated = named.update(sql, params);
        if (updated != 1) {
            throw new ConcurrentTransitionException(request.taskId(), from, to, spec.code());
        }

        if (spec.effect() == TaskTransitionEffect.STABLE_FILE) {
            writeFileVersionStableAt(request);
        }
        if (request.attempt() != null) {
            writeAttempt(request);
        }
        writeEvent(request, spec, from, to);
    }

    /** 按副作用拼接 SET 片段（status/updated_at 已在外层加入）。 */
    private static void applyEffect(TaskTransitionEffect effect, TransitionRequest request,
            MapSqlParameterSource params, List<String> sets) {
        switch (effect) {
            case NONE -> {
                // 仅状态迁移
            }
            case CLAIM -> {
                if (request.workerId() == null || request.leaseUntil() == null) {
                    throw new IllegalArgumentException(
                            "claim transition (" + request.code() + ") requires workerId and leaseUntil");
                }
                sets.add("worker_id = :workerId");
                params.addValue("workerId", request.workerId());
                sets.add("claimed_at = now()");
                sets.add("lease_until = :leaseUntil");
                bindTimestamp(params, "leaseUntil", request.leaseUntil());
                sets.add("started_at = COALESCE(started_at, now())");
                sets.add("next_retry_at = NULL");
            }
            case RELEASE -> release(sets);
            case S3_COMMITTED -> {
                if (request.s3UploadedAt() != null) {
                    sets.add("s3_uploaded_at = :s3UploadedAt");
                    bindTimestamp(params, "s3UploadedAt", request.s3UploadedAt());
                } else {
                    sets.add("s3_uploaded_at = now()");
                }
            }
            case FAIL_BACKOFF -> {
                sets.add("retry_count = retry_count + 1");
                sets.add("next_retry_at = :nextRetryAt");
                bindNullableTimestamp(params, "nextRetryAt", request.nextRetryAt());
                sets.add("last_attempt_finished_at = now()");
                releaseLeaseOnly(sets);
            }
            case COMPLETE -> {
                sets.add("completed_at = now()");
                release(sets);
            }
            case CANCEL -> {
                sets.add("cancelled_at = now()");
                sets.add("cancel_reason = :cancelReason");
                params.addValue("cancelReason", request.cancelReason());
                if (request.supersededByVersionNo() != null) {
                    sets.add("superseded_by_version_no = :supersededByVersionNo");
                    params.addValue("supersededByVersionNo", request.supersededByVersionNo());
                } else {
                    sets.add("superseded_by_version_no = NULL");
                }
                release(sets);
            }
            case STABLE_FILE -> {
                // file_version.stable_at 由 writeFileVersionStableAt 在同一事务内更新
            }
            default -> throw new IllegalStateException("unknown effect " + effect);
        }
    }

    private static void release(List<String> sets) {
        sets.add("worker_id = NULL");
        sets.add("claimed_at = NULL");
        sets.add("lease_until = NULL");
        sets.add("next_retry_at = NULL");
    }

    /** 仅释放所有权，不动 next_retry_at（FAIL_BACKOFF 已单独写退避时间）。 */
    private static void releaseLeaseOnly(List<String> sets) {
        sets.add("worker_id = NULL");
        sets.add("claimed_at = NULL");
        sets.add("lease_until = NULL");
    }

    private static void bindTimestamp(MapSqlParameterSource params, String name, OffsetDateTime value) {
        params.addValue(name, value, Types.TIMESTAMP_WITH_TIMEZONE);
    }

    private static void bindNullableTimestamp(MapSqlParameterSource params, String name,
            OffsetDateTime value) {
        if (value == null) {
            params.addValue(name, null, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            params.addValue(name, value, Types.TIMESTAMP_WITH_TIMEZONE);
        }
    }

    /** T4：同一事务把该 task 对应 file_version 标记稳定（stable_at 非空）。 */
    private void writeFileVersionStableAt(TransitionRequest request) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("id", request.taskId());
        if (request.fileVersionStableAt() != null) {
            bindTimestamp(params, "stableAt", request.fileVersionStableAt());
            named.update("UPDATE file_version SET stable_at = :stableAt, updated_at = now()"
                    + " WHERE id = (SELECT file_version_id FROM transfer_task WHERE id = :id)", params);
        } else {
            named.update("UPDATE file_version SET stable_at = now(), updated_at = now()"
                    + " WHERE id = (SELECT file_version_id FROM transfer_task WHERE id = :id)", params);
        }
    }

    /** 可选：同一事务落一次 transfer_attempt（Phase 7/8 的 Worker 回写结果用）。 */
    private void writeAttempt(TransitionRequest request) {
        AttemptWrite a = request.attempt();
        attemptRepository.insert(new TransferAttemptRow(null, request.taskId(),
                a.attemptType(), a.attemptNo(),
                a.startedAt() != null ? a.startedAt() : OffsetDateTime.now(),
                a.finishedAt(), a.success(), a.outcome(), a.httpStatus(), a.errorCode(),
                a.errorMessage(), null, a.gatewayRequestId(), a.durationMs(), null));
    }

    /** 审计：每个迁移写一条 transfer_event（operator 默认 system）。 */
    private void writeEvent(TransitionRequest request, TaskTransitionSpec spec,
            TaskStatus from, TaskStatus to) {
        String operator = request.operator() != null ? request.operator() : "system";
        String reason = request.reason() != null ? request.reason() : spec.code();
        eventRepository.insert(new TransferEventRow(null, request.taskId(),
                from.name(), to.name(), reason, operator, null, null));
    }
}
