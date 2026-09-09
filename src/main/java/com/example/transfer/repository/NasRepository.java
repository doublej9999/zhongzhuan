package com.example.transfer.repository;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

/** nas 表数据访问（JdbcTemplate + SimpleJdbcInsert）。 */
@Repository
public class NasRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<NasRow> MAPPER = (rs, rowNum) -> new NasRow(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("mount_path"),
            rs.getBoolean("enabled"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    public NasRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一行：列清单 = 显式 usingColumns（排除 id/created_at/updated_at，由 DB 默认
     * now() 填充），返回自增主键。
     */
    public long insert(NasRow row) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("name", row.name());
        params.addValue("mount_path", row.mountPath());
        params.addValue("enabled", row.enabled());
        return new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("nas")
                .usingGeneratedKeyColumns("id")
                .usingColumns("name", "mount_path", "enabled")
                .executeAndReturnKey(params).longValue();
    }

    public Optional<NasRow> findById(long id) {
        return jdbcTemplate.query("SELECT * FROM nas WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 按逻辑名称查询（uq_nas_name 保证唯一）。 */
    public Optional<NasRow> findByName(String name) {
        return jdbcTemplate.query("SELECT * FROM nas WHERE name = ?", MAPPER, name)
                .stream().findFirst();
    }
}
