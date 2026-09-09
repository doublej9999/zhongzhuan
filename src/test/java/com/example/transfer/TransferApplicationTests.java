package com.example.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.transfer.config.AppProperties;

/**
 * Phase 1 骨架冒烟测试：
 * <ul>
 *   <li>Spring 上下文可完整启动（含 Flyway 空迁移 + Hikari 数据源）；</li>
 *   <li>真实连接 PostgreSQL（Docker localhost:5432 / nas_s3_gateway）并执行 SELECT 1；</li>
 *   <li>{@code app.*} 配置绑定生效。</li>
 * </ul>
 *
 * <p>依赖本地 Docker 的 postgres 容器与 gitignored 的 application-local.yml（或环境变量
 * {@code DB_PASSWORD}）。</p>
 */
@SpringBootTest
class TransferApplicationTests {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AppProperties appProperties;

    @Test
    void contextLoadsWithDataSourceAndFlyway() throws Exception {
        assertNotNull(dataSource);
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT 1")) {
            assertTrue(rs.next(), "SELECT 1 应返回一行");
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void appPropertiesAreBound() {
        assertNotNull(appProperties.getInstanceId());
        assertNotNull(appProperties.getWorkerIdPrefix());
        assertNotNull(appProperties.getTimeZone());
        assertEquals("Asia/Shanghai", appProperties.getTimeZone());
        assertEquals("Asia/Shanghai", appProperties.zoneId().getId());
    }
}
