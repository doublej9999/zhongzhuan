package com.example.transfer.config;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 顶层 {@code app.*} 配置绑定（对应 07-configuration-model 的 instance/worker 前缀约定）。
 *
 * <p>敏感字段（S3/Gateway/DB 凭据）一律不在此绑定，只经环境变量注入。</p>
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** 单机唯一实例标识，如 {@code nas-s3-gateway-01}。 */
    private String instanceId = "nas-s3-gateway-01";

    /** worker_id 前缀，如 {@code worker}。 */
    private String workerIdPrefix = "worker";

    /** 全局时区，影响 mtime 解析与日志时间展示。 */
    private String timeZone = "Asia/Shanghai";

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getWorkerIdPrefix() {
        return workerIdPrefix;
    }

    public void setWorkerIdPrefix(String workerIdPrefix) {
        this.workerIdPrefix = workerIdPrefix;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    /** 解析后的时区；非法值由 ZoneId#of 抛异常（启动即失败，符合 fail-fast）。 */
    public ZoneId zoneId() {
        return ZoneId.of(timeZone);
    }
}
