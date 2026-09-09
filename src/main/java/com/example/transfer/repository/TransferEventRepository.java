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

/** transfer_event 表数据访问（可选审计：状态迁移 / operator 留痕）。 */
@Repository
public class TransferEventRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<TransferEventRow> MAPPER = (rs, rowNum) -> new TransferEventRow(
            rs.getLong("id"),
            rs.getLong("transfer_task_id"),
            rs.getString("from_status"),
            rs.getString("to_status"),
            rs.getString("reason"),
            rs.getString("operator"),
            rs.getString("detail"),
            rs.getObject("created_at", OffsetDateTime.class));

    public TransferEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一行：usingColumns 只含非 null 业务列，排除 id/created_at
     * （created_at 由 DB 默认 now()；fromStatus/reason/detail 为空时落 NULL），返回自增主键。
     */
    public long insert(TransferEventRow row) {
        List<String> cols = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        put(params, cols, "transfer_task_id", row.transferTaskId());
        put(params, cols, "from_status", row.fromStatus());
        put(params, cols, "to_status", row.toStatus());
        put(params, cols, "reason", row.reason());
        put(params, cols, "operator", row.operator());
        put(params, cols, "detail", row.detail());
        return new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("transfer_event")
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

    public Optional<TransferEventRow> findById(long id) {
        return jdbcTemplate.query("SELECT * FROM transfer_event WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 按任务查询审计事件（按 id 升序 = 时间顺序）。 */
    public List<TransferEventRow> findByTransferTaskId(long transferTaskId) {
        return jdbcTemplate.query(
                "SELECT * FROM transfer_event WHERE transfer_task_id = ? ORDER BY id",
                MAPPER, transferTaskId);
    }
}
