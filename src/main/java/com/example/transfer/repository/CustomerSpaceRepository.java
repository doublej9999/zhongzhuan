package com.example.transfer.repository;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

/** customer_space 表数据访问。 */
@Repository
public class CustomerSpaceRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<CustomerSpaceRow> MAPPER = (rs, rowNum) -> new CustomerSpaceRow(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getBoolean("enabled"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    public CustomerSpaceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一行：列清单 = 显式 usingColumns（排除 id/created_at/updated_at，由 DB 默认
     * now() 填充），返回自增主键。
     */
    public long insert(CustomerSpaceRow row) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("code", row.code());
        params.addValue("name", row.name());
        params.addValue("enabled", row.enabled());
        return new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("customer_space")
                .usingGeneratedKeyColumns("id")
                .usingColumns("code", "name", "enabled")
                .executeAndReturnKey(params).longValue();
    }

    public Optional<CustomerSpaceRow> findById(long id) {
        return jdbcTemplate.query("SELECT * FROM customer_space WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 按 code 查询（uq_customer_space_code 保证唯一）。 */
    public Optional<CustomerSpaceRow> findByCode(String code) {
        return jdbcTemplate.query("SELECT * FROM customer_space WHERE code = ?", MAPPER, code)
                .stream().findFirst();
    }
}
