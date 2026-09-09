package com.example.transfer.repository;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

/** file 表数据访问。 */
@Repository
public class FileRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<FileRow> MAPPER = (rs, rowNum) -> new FileRow(
            rs.getLong("id"),
            rs.getLong("nas_id"),
            rs.getLong("directory_id"),
            rs.getString("relative_path"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    public FileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一行：列清单 = 显式 usingColumns（排除 id/created_at/updated_at，由 DB 默认
     * now() 填充），返回自增主键。
     */
    public long insert(FileRow row) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("nas_id", row.nasId());
        params.addValue("directory_id", row.directoryId());
        params.addValue("relative_path", row.relativePath());
        return new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("file")
                .usingGeneratedKeyColumns("id")
                .usingColumns("nas_id", "directory_id", "relative_path")
                .executeAndReturnKey(params).longValue();
    }

    public Optional<FileRow> findById(long id) {
        return jdbcTemplate.query("SELECT * FROM file WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<FileRow> findByPath(long nasId, long directoryId, String relativePath) {
        return jdbcTemplate.query(
                "SELECT * FROM file WHERE nas_id = ? AND directory_id = ? AND relative_path = ?",
                MAPPER, nasId, directoryId, relativePath)
                .stream().findFirst();
    }

    public FileRow findOrCreate(long nasId, long directoryId, String relativePath) {
        return findByPath(nasId, directoryId, relativePath).orElseGet(() -> {
            try {
                long id = insert(new FileRow(null, nasId, directoryId, relativePath, null, null));
                return findById(id).orElseThrow();
            } catch (org.springframework.dao.DuplicateKeyException e) {
                return findByPath(nasId, directoryId, relativePath).orElseThrow();
            }
        });
    }
}
