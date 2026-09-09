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

/** directory 表数据访问。 */
@Repository
public class DirectoryRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<DirectoryRow> MAPPER = (rs, rowNum) -> new DirectoryRow(
            rs.getLong("id"),
            rs.getLong("nas_id"),
            rs.getLong("customer_space_id"),
            rs.getString("code"),
            rs.getString("path"),
            rs.getString("extensions"),
            rs.getLong("scan_interval_sec"),
            rs.getInt("max_concurrency"),
            rs.getBoolean("enabled"),
            rs.getObject("last_scan_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    public DirectoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一行：usingColumns 只含非 null 业务列，排除 id/created_at/updated_at 与空值列
     * （extensions/last_scan_at 为空时由 DB 默认填充），返回自增主键。
     */
    public long insert(DirectoryRow row) {
        List<String> cols = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        put(params, cols, "nas_id", row.nasId());
        put(params, cols, "customer_space_id", row.customerSpaceId());
        put(params, cols, "code", row.code());
        put(params, cols, "path", row.path());
        put(params, cols, "extensions", row.extensions());
        put(params, cols, "scan_interval_sec", row.scanIntervalSec());
        put(params, cols, "max_concurrency", row.maxConcurrency());
        put(params, cols, "enabled", row.enabled());
        put(params, cols, "last_scan_at", row.lastScanAt());
        return new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("directory")
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

    public Optional<DirectoryRow> findById(long id) {
        return jdbcTemplate.query("SELECT * FROM directory WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 按 code 查询（uq_directory_code 保证唯一）。 */
    public Optional<DirectoryRow> findByCode(String code) {
        return jdbcTemplate.query("SELECT * FROM directory WHERE code = ?", MAPPER, code)
                .stream().findFirst();
    }
}
