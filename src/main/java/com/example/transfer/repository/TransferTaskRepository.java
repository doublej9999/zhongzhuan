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

/** transfer_task 表数据访问（9 态状态机；唯一权威任务状态）。 */
@Repository
public class TransferTaskRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<TransferTaskRow> MAPPER = (rs, rowNum) -> new TransferTaskRow(
            rs.getLong("id"),
            rs.getLong("file_version_id"),
            rs.getLong("file_id"),
            rs.getInt("version_no"),
            rs.getLong("directory_id"),
            rs.getLong("customer_space_id"),
            rs.getString("file_path"),
            rs.getLong("file_size_bytes"),
            rs.getObject("file_mtime", OffsetDateTime.class),
            rs.getString("s3_bucket"),
            rs.getString("s3_object_key"),
            rs.getObject("s3_uploaded_at", OffsetDateTime.class),
            rs.getString("gateway_route"),
            rs.getString("config_version"),
            TaskStatus.valueOf(rs.getString("status")),
            rs.getInt("priority"),
            rs.getInt("retry_count"),
            rs.getObject("next_retry_at", OffsetDateTime.class),
            rs.getString("worker_id"),
            rs.getObject("claimed_at", OffsetDateTime.class),
            rs.getObject("lease_until", OffsetDateTime.class),
            rs.getObject("last_attempt_finished_at", OffsetDateTime.class),
            rs.getString("cancel_reason"),
            (Integer) rs.getObject("superseded_by_version_no"),
            rs.getObject("cancelled_at", OffsetDateTime.class),
            rs.getObject("started_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    public TransferTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一行：usingColumns 只含非 null 业务列，排除 id/created_at/updated_at
     * （created_at/updated_at 由 DB 默认 now()；其余空值列落 NULL），返回自增主键。
     */
    public long insert(TransferTaskRow row) {
        List<String> cols = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        put(params, cols, "file_version_id", row.fileVersionId());
        put(params, cols, "file_id", row.fileId());
        put(params, cols, "version_no", row.versionNo());
        put(params, cols, "directory_id", row.directoryId());
        put(params, cols, "customer_space_id", row.customerSpaceId());
        put(params, cols, "file_path", row.filePath());
        put(params, cols, "file_size_bytes", row.fileSizeBytes());
        put(params, cols, "file_mtime", row.fileMtime());
        put(params, cols, "s3_bucket", row.s3Bucket());
        put(params, cols, "s3_object_key", row.s3ObjectKey());
        put(params, cols, "s3_uploaded_at", row.s3UploadedAt());
        put(params, cols, "gateway_route", row.gatewayRoute());
        put(params, cols, "config_version", row.configVersion());
        put(params, cols, "status", row.status() == null ? null : row.status().name());
        put(params, cols, "priority", row.priority());
        put(params, cols, "retry_count", row.retryCount());
        put(params, cols, "next_retry_at", row.nextRetryAt());
        put(params, cols, "worker_id", row.workerId());
        put(params, cols, "claimed_at", row.claimedAt());
        put(params, cols, "lease_until", row.leaseUntil());
        put(params, cols, "last_attempt_finished_at", row.lastAttemptFinishedAt());
        put(params, cols, "cancel_reason", row.cancelReason());
        put(params, cols, "superseded_by_version_no", row.supersededByVersionNo());
        put(params, cols, "cancelled_at", row.cancelledAt());
        put(params, cols, "started_at", row.startedAt());
        put(params, cols, "completed_at", row.completedAt());
        return new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("transfer_task")
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

    public Optional<TransferTaskRow> findById(long id) {
        return jdbcTemplate.query("SELECT * FROM transfer_task WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 按 file_version_id 查询（uq_transfer_task_file_version 保证唯一）。 */
    public Optional<TransferTaskRow> findByFileVersionId(long fileVersionId) {
        return jdbcTemplate
                .query("SELECT * FROM transfer_task WHERE file_version_id = ?", MAPPER, fileVersionId)
                .stream().findFirst();
    }
}
