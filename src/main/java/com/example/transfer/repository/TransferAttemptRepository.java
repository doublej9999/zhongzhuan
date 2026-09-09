package com.example.transfer.repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

/** transfer_attempt 表数据访问（同一 (task, type, attempt_no) 唯一）。 */
@Repository
public class TransferAttemptRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<TransferAttemptRow> MAPPER = (rs, rowNum) -> new TransferAttemptRow(
            rs.getLong("id"),
            rs.getLong("transfer_task_id"),
            AttemptType.valueOf(rs.getString("attempt_type")),
            rs.getInt("attempt_no"),
            rs.getObject("started_at", OffsetDateTime.class),
            rs.getObject("finished_at", OffsetDateTime.class),
            rs.getBoolean("success"),
            AttemptOutcome.valueOf(rs.getString("outcome")),
            (Integer) rs.getObject("http_status"),
            rs.getString("error_code"),
            rs.getString("error_message"),
            rs.getString("request_id"),
            rs.getString("gateway_request_id"),
            (Long) rs.getObject("duration_ms"),
            rs.getObject("created_at", OffsetDateTime.class));

    public TransferAttemptRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一行：usingColumns 只含非 null 业务列，排除 id/created_at
     * （created_at 由 DB 默认 now()；其余空值列落 NULL），返回自增主键。
     */
    public long insert(TransferAttemptRow row) {
        List<String> cols = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        put(params, cols, "transfer_task_id", row.transferTaskId());
        put(params, cols, "attempt_type", row.attemptType() == null ? null : row.attemptType().name());
        put(params, cols, "attempt_no", row.attemptNo());
        put(params, cols, "started_at", row.startedAt());
        put(params, cols, "finished_at", row.finishedAt());
        put(params, cols, "success", row.success());
        put(params, cols, "outcome", row.outcome() == null ? null : row.outcome().name());
        put(params, cols, "http_status", row.httpStatus());
        put(params, cols, "error_code", row.errorCode());
        put(params, cols, "error_message", row.errorMessage());
        put(params, cols, "request_id", row.requestId());
        put(params, cols, "gateway_request_id", row.gatewayRequestId());
        put(params, cols, "duration_ms", row.durationMs());
        return new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("transfer_attempt")
                .usingGeneratedKeyColumns("id")
                .usingColumns(cols.toArray(String[]::new))
                .executeAndReturnKey(params).longValue();
    }

    /** 仅 addValue 非 null 的列，并记录到列清单；OffsetDateTime 显式按 TIMESTAMPTZ 绑定。 */
    private static void put(MapSqlParameterSource params, List<String> cols, String column, Object value) {
        if (value == null) {
            return;
        }
        cols.add(column);
        if (value instanceof OffsetDateTime odt) {
            params.addValue(column, odt, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            params.addValue(column, value);
        }
    }

    public Optional<TransferAttemptRow> findById(long id) {
        return jdbcTemplate.query("SELECT * FROM transfer_attempt WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }
}
