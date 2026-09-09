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

/** file_version 表数据访问。 */
@Repository
public class FileVersionRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<FileVersionRow> MAPPER = (rs, rowNum) -> new FileVersionRow(
            rs.getLong("id"),
            rs.getLong("file_id"),
            rs.getInt("version_no"),
            rs.getLong("size_bytes"),
            rs.getObject("mtime", OffsetDateTime.class),
            rs.getString("fingerprint"),
            rs.getObject("first_seen_at", OffsetDateTime.class),
            rs.getObject("stable_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    public FileVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一行：usingColumns 只含非 null 业务列，排除 id/created_at/updated_at 与空值列
     * （first_seen_at/stable_at 为空时由 DB 默认 now() / NULL 填充），返回自增主键。
     */
    public long insert(FileVersionRow row) {
        List<String> cols = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        put(params, cols, "file_id", row.fileId());
        put(params, cols, "version_no", row.versionNo());
        put(params, cols, "size_bytes", row.sizeBytes());
        put(params, cols, "mtime", row.mtime());
        put(params, cols, "fingerprint", row.fingerprint());
        put(params, cols, "first_seen_at", row.firstSeenAt());
        put(params, cols, "stable_at", row.stableAt());
        return new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("file_version")
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

    public Optional<FileVersionRow> findById(long id) {
        return jdbcTemplate.query("SELECT * FROM file_version WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<FileVersionRow> findLatestByFileId(long fileId) {
        return jdbcTemplate.query(
                "SELECT * FROM file_version WHERE file_id = ? ORDER BY version_no DESC LIMIT 1",
                MAPPER, fileId)
                .stream().findFirst();
    }

    public void updateMetadata(long id, long sizeBytes, OffsetDateTime mtime, String fingerprint) {
        jdbcTemplate.update(
                "UPDATE file_version SET size_bytes = ?, mtime = ?, fingerprint = ?, updated_at = now() WHERE id = ?",
                sizeBytes, mtime, fingerprint, id);
    }
}
