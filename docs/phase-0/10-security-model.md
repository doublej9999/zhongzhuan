# 10 Security Model（安全模型）

> 权威事实源：`phase0-baseline.md`（§0 项目坐标、§5 S3 key、§8 保留、§10 开放问题）。
> 已确认架构：`NAS → S3 → B2B Gateway → SFTP 中转系统整体技术设计.md`（§22、§54–§57、§78–§79）。
> 冲突时以上述两份文档为准；本文仅做安全维度的展开，不修改架构。
> 本文出现的所有凭据一律为占位符，**不得**写入任何真实值。

---

## 本部分范围

- 定义 V1（单 JVM + PostgreSQL + SMB + S3 + Gateway）安全模型，覆盖 7 个主题：
  1. 密钥管理；2. 数据库凭据与最小权限；3. 传输加密；4. 日志脱敏；
  5. 认证与授权；6. 数据保护与保留；7. 密钥轮换与泄露响应。
- 每个主题给出 `威胁/风险 → 控制措施 → 验证方式 → 责任方` 四要素，条目编号 `SEC-xx`。
- 给出 STRIDE 简化威胁建模、上线前安全检查清单、安全反模式清单。
- 明确与已确认架构冲突或未覆盖之处，统一标注 `【架构问题】`。

## 本部分不做什么

- **不写业务代码、DDL、`pom.xml`、`application.yml` 实体文件**；文中的 SQL / 配置片段均为**示意，非交付物**。
- **不定义任何真实凭据**；不给出可复制的密钥值、密码、token。
- 不引入 V1 明确不引入的组件：Kafka / RabbitMQ / Redis / Kubernetes / 分布式锁 / 分布式事务 / S3 对象版本控制 / 内容 hash / Admin 前端。
- 不做等保、ISO 27001、GDPR 等合规认证的正式结论；只提供可客观验证的控制点。
- 不定义 B2B Gateway 服务端内部实现、客户 SFTP 侧账号与权限（属对方团队）。
- 不替代运维手册；部署、密钥分发流程由运维文档承载。

## 对应整体设计文档章节

| 本文主题 | 对应章节 | 说明 |
|---|---|---|
| 密钥管理 | §56 敏感信息、§78 配置修改 | 环境变量注入，配置快照只含非敏感配置 |
| 数据库凭据与最小权限 | §33 数据模型（DDL 相关）、§54 数据保留 | 应用账号与 migration 账号分离 |
| 传输加密 | §22 Gateway API、§4 S3 上传 | S3 HTTPS、Gateway HTTPS、NAS SMB |
| 日志脱敏 | §55 不建议 PostgreSQL 保存应用日志、§57 日志设计 | 日志字段白名单与脱敏 |
| 认证与授权 | §22 Gateway API、§80 V2 Admin API | Gateway 认证、S3 认证、manual retry 鉴权 |
| 数据保护与保留 | §54 数据保留、§5 S3 Object Key、§8 保留结论 | 1 年、仅清理终态、无版本控制 |
| 密钥轮换与泄露响应 | §56、§78 | 凭据不进任务快照 |

### 占位符约定（全文统一）

| 占位符 | 含义 |
|---|---|
| `${S3_ACCESS_KEY}` / `${S3_SECRET_KEY}` | S3 访问凭据 |
| `${GATEWAY_APP_ID}` / `${GATEWAY_APP_KEY}` | B2B Gateway 应用凭据 |
| `${DB_APP_USERNAME}` / `${DB_APP_PASSWORD}` | 应用数据库账号 |
| `${DB_MIGRATION_USERNAME}` / `${DB_MIGRATION_PASSWORD}` | 迁移专用账号 |
| `${OPERATOR_TOKEN}` | manual retry 操作凭据（V1 内部使用） |
| `<redacted>` | 日志脱敏后的替代值 |

---

## 密钥管理

### 威胁 / 风险

- 凭据硬编码进 `application.yml` 并随代码进入 Git 历史，一旦仓库外泄即全量失陷。
- 凭据写入 PostgreSQL（如配置表、任务快照 JSON），使 DB 备份、只读账号、慢查询日志都成为泄露面。
- 凭据进入日志（DEBUG 打印配置对象、异常堆栈、HTTP client 日志）。
- 凭据进入 `transfer_task` 的配置快照，导致历史任务永久携带可复用密钥。

### 控制措施

| 编号 | 威胁/风险 | 控制措施 | 验证方式 | 责任方 |
|---|---|---|---|---|
| SEC-01 | 凭据硬编码 / 进 Git | 仅通过环境变量或 Secret 注入；仓库内只允许占位符；`.gitignore` 排除本地凭据文件；提交前扫描 | `git grep -nE '(secret-key\|app-key\|password)\s*:\s*[^\$]'` 无命中（即所有赋值均为 `${...}` 占位符）；CI 上执行同一扫描 | 开发 + CI |
| SEC-02 | 凭据进 PostgreSQL | 数据库 7 张表（`nas`/`customer_space`/`directory`/`file`/`file_version`/`transfer_task`/`transfer_attempt`）及可选 `transfer_event` **均不得**存在凭据列；配置快照只存非敏感项 | 对全表 `information_schema.columns` 人工核对 + 抽样 `SELECT` 快照字段，确认无密钥类字段 | 架构 + DBA |
| SEC-03 | 凭据进日志 | 日志框架对 `Authorization`、`AppKey`、`SecretKey`、`password` 关键字做全局脱敏；HTTP client 关闭 body/header 日志 | 用测试环境注入假凭据跑全链路，`grep -i` 日志文件无命中 | 开发 |
| SEC-04 | 凭据进任务快照 | `config_version` 只标识**非敏感**配置（并发、超时、退避、宽限期等）；凭据永不进入快照 | 检查快照序列化白名单字段，确认凭据字段不在其中 | 开发 |
| SEC-05 | 运行时凭据泄漏到进程环境 | 生产用 systemd `Environment=`/`EnvironmentFile`（权限 600）或容器 Secret 注入；禁止 `docker run -e` 明文出现在 shell history | 检查部署单元与 shell history；`ps -ef` 不出现凭据 | 运维 |
| SEC-06 | 凭据被非授权人读取 | 最小知悉：仅部署系统与必要运维可见；本地开发用独立测试凭据，禁止复用生产凭据 | 凭据清单与持有人清单比对 | 安全 + 运维 |
| SEC-07 | 调试开关导致凭据外泄 | 禁止在生产开启 `DEBUG`/`TRACE`；禁止 `logging.level.org.apache.http=DEBUG`、`software.amazon.awssdk=DEBUG` | 检查生产 `application.yml` 与启动参数 | 运维 |

### 环境变量名表（唯一允许的注入通道）

| 环境变量 | 用途 | 敏感 | 是否进入任务快照 |
|---|---|---|---|
| `S3_ENDPOINT` | S3 端点（必须 `https://`） | 否 | 否 |
| `S3_REGION` | S3 区域 | 否 | 否 |
| `S3_BUCKET` | 目标桶 | 否 | 是 |
| `S3_ACCESS_KEY` | S3 AccessKey | **是** | 否 |
| `S3_SECRET_KEY` | S3 SecretKey | **是** | 否 |
| `GATEWAY_ENDPOINT` | Gateway 地址（必须 `https://`） | 否 | 否 |
| `GATEWAY_APP_ID` | Gateway AppId | **是** | 否 |
| `GATEWAY_APP_KEY` | Gateway AppKey | **是** | 否 |
| `DB_URL` | JDBC URL（含 `sslmode=require` 及以上） | 否 | 否 |
| `DB_APP_USERNAME` | 应用 DB 账号 | 半敏感 | 否 |
| `DB_APP_PASSWORD` | 应用 DB 密码 | **是** | 否 |
| `DB_MIGRATION_USERNAME` | 迁移账号 | 半敏感 | 否 |
| `DB_MIGRATION_PASSWORD` | 迁移账号密码 | **是** | 否 |
| `OPERATOR_TOKEN` | manual retry 操作凭据 | **是** | 否 |

### `application.yml` 占位符写法（示意，非交付物）

```yaml
# 示意，非交付物；不得写入真实值
s3:
  endpoint: ${S3_ENDPOINT}
  access-key: ${S3_ACCESS_KEY}
  secret-key: ${S3_SECRET_KEY}
gateway:
  app-id: ${GATEWAY_APP_ID}
  app-key: ${GATEWAY_APP_KEY}
spring:
  datasource:
    username: ${DB_APP_USERNAME}
    password: ${DB_APP_PASSWORD}
    url: ${DB_URL}
```

- 占位符未注入时必须**启动失败**（fail-fast），不得回退到默认值或空串。
- 禁止写成 `${S3_SECRET_KEY:defaultValue}` 形式（提供默认值等于把凭据写进代码库）。

### 责任方汇总

开发（代码与日志）、运维（注入与主机）、DBA（库侧）、安全（审计与轮换审批）。

---

## 数据库凭据与最小权限

### 威胁 / 风险

- 应用账号为 superuser：任一 SQL 注入或代码缺陷即导致整库（含其他库）失陷。
- 应用账号可 `DROP`/`TRUNCATE`/`ALTER`：误操作或恶意代码直接破坏唯一 Source of Truth。
- 应用账号承担 migration：DDL 权限长期在线，攻击面扩大。
- 明文或未加密连接：同网段嗅探、DB 侧日志记录明文凭据。

### 控制措施

| 编号 | 威胁/风险 | 控制措施 | 验证方式 | 责任方 |
|---|---|---|---|---|
| SEC-08 | 应用账号过度授权 | 应用账号仅授予业务表 `SELECT/INSERT/UPDATE/DELETE` + 序列 `USAGE/SELECT`；**不得** `SUPERUSER`/`CREATEDB`/`CREATEROLE` | `SELECT rolsuper, rolcreatedb, rolcreaterole FROM pg_roles WHERE rolname='${DB_APP_USERNAME}'` 全为 `f` | DBA |
| SEC-09 | 应用账号持有 DDL | DDL 由**独立 migration 账号**在部署/发布阶段执行；应用运行期不持有 `CREATE/ALTER/DROP` | `\dp` 或 `has_table_privilege` 核查；应用启动日志无 DDL 语句 | DBA + 开发 |
| SEC-10 | 应用账号 `TRUNCATE` 导致全表清空 | 数据保留清理按 §54 逐级 `DELETE`（`transfer_event → transfer_attempt → transfer_task → file_version → file`），不授予 `TRUNCATE` | `has_table_privilege('${DB_APP_USERNAME}','transfer_task','TRUNCATE')` 为 `f` | DBA |
| SEC-11 | 默认权限导致新表越权 | 显式 `ALTER DEFAULT PRIVILEGES` 限定未来表；`REVOKE ALL ON SCHEMA public FROM PUBLIC` | 新建测试表后验证应用账号仅有 DML | DBA |
| SEC-12 | 连接未加密 | JDBC URL 必须 `sslmode=require` 及以上（生产建议 `verify-full`）；禁止 `sslmode=disable`/`allow`/`prefer` | 抓包或 DB 侧 `pg_stat_ssl.ssl = true`；配置审查 | 运维 + DBA |
| SEC-13 | 凭据复用 | 应用账号、migration 账号、监控只读账号各自独立；禁止共享同一账号 | 账号清单比对；审计日志区分 `usename` | DBA + 安全 |

### 权限示意（示意，非 DDL 交付物）

```sql
-- 示意，非 DDL 交付物；密码由 Secret 注入，禁止硬编码
CREATE ROLE transfer_app LOGIN PASSWORD '${DB_APP_PASSWORD}' NOSUPERUSER NOCREATEDB NOCREATEROLE;
GRANT CONNECT ON DATABASE nas_s3_gateway TO transfer_app;
GRANT USAGE ON SCHEMA public TO transfer_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO transfer_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO transfer_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
-- 不授予：TRUNCATE / ALTER / DROP / CREATE / SUPERUSER / CREATEDB / CREATEROLE
```

### 责任方汇总

DBA（角色与权限）、运维（连接与 TLS）、开发（SQL 与事务边界）。

---

## 传输加密

### 威胁 / 风险

- 明文 HTTP 调 Gateway：AppId/AppKey 与对象 key 在网络中可被嗅探，投递请求可被中间人篡改。
- HTTP 访问 S3 端点：对象内容与签名头明文暴露。
- TLS 校验被关闭（`trustAllCerts` / `verify=false`）：中间人可解密、替换 S3 对象，进而把恶意文件投递给客户。
- NAS SMB 未签名/未加密：同网段可篡改或读取源文件。

### 控制措施

| 编号 | 威胁/风险 | 控制措施 | 验证方式 | 责任方 |
|---|---|---|---|---|
| SEC-14 | S3 明文 HTTP | `S3_ENDPOINT` 必须 `https://`；启动时校验 scheme，非 https 直接 fail-fast | 配置审查 + 启动日志；`grep -n 'http://' application.yml` 无命中 | 开发 + 运维 |
| SEC-15 | Gateway 明文 HTTP | `GATEWAY_ENDPOINT` 必须 `https://`；禁止 HTTP 回退与重定向跟随到 http | 配置审查 + 集成测试断言 | 开发 |
| SEC-16 | TLS 校验被绕过 | 禁止 `trustAllCerts`、`X509TrustManager` 空实现、`HostnameVerifier` 恒真、`-Djavax.net.ssl.trustAll` | 代码扫描关键字；代码评审必查 | 开发 + 评审 |
| SEC-17 | 弱协议/弱套件 | 仅允许 TLS 1.2+（建议 1.3）；禁用 SSLv3/TLS1.0/1.1 | `openssl s_client` 探测；JDK `jdk.tls.disabledAlgorithms` 核查 | 运维 + 开发 |
| SEC-18 | NAS SMB 明文/未签名 | 建议 `vers=3.1.1` + 强制签名（signing required）；有条件时启用 SMB 加密（`seal`）；凭据放 `credentials` 文件（权限 600），禁止写在 `fstab` 明文 | `mount` 输出核查选项；`smbstatus`/抓包验证签名 | 运维 |
| SEC-19 | 传输层降级 | 关闭 HTTP 自动重定向到 HTTP；HTTP 客户端禁止 `followRedirects(NORMAL)` 跨 scheme 降级 | 单测断言重定向策略 | 开发 |
| SEC-20 | S3 对象静态加密缺失 | 建议桶级默认 SSE-S3（或 SSE-KMS，视 S3 兼容性确认）；不引入客户端自研加密 | 桶策略/默认加密配置核查 | 运维 + 安全 |

### 【架构问题】SMB 安全基线未定义

- **问题**：整体设计文档仅说明「NAS 已由操作系统挂载（SMB/CIFS）」，未约束 SMB 版本、签名、加密与挂载凭据存放方式。
- **风险**：若使用 SMB1 或关闭签名，同网段可读取/篡改源文件；`fstab` 明文密码会成为主机级泄露点。
- **建议方案**：在部署文档中固化 SMB 基线（`vers=3.1.1`、签名必需、可选 `seal`、`credentials` 文件 600、专用只读挂载账号）。
- **对现有设计的影响**：仅影响部署配置，不改代码与数据模型；不引入新组件。

### 责任方汇总

运维（主机与挂载）、开发（客户端 TLS 策略）、安全（基线审批）。

---

## 日志脱敏

### 威胁 / 风险

- 日志打印完整请求头：`Authorization`、`AppKey`、`Cookie` 落入日志系统，日志系统成为凭据仓库。
- 日志打印完整 URL query：若 Gateway 认证走 query string（见开放问题 Q3），凭据会被完整记录。
- 日志打印响应体/请求体：可能包含客户文件路径、客户名等敏感元数据，且体积失控。
- 日志打印 `SecretKey`/密码：一旦日志外送（Loki/ELK/Splunk），泄露不可撤回。

### 必须脱敏字段清单

| 字段/模式 | 处理方式 |
|---|---|
| `Authorization`（含 Basic/Bearer/签名） | 整值替换为 `<redacted>` |
| `AppKey` / `AppId` | 保留前 4 位 + `<redacted>` |
| `SecretKey` / `SecretAccessKey` / `accessKey` | 整值 `<redacted>` |
| `password` / `passwd` / `pwd` | 整值 `<redacted>` |
| `token` / `OPERATOR_TOKEN` | 整值 `<redacted>` |
| 完整 URL query（`?...`） | 整段替换为 `?<redacted>` |
| `X-Amz-Signature` / `X-Amz-Credential` 等签名参数 | 整值 `<redacted>` |
| Cookie / Set-Cookie | 整值 `<redacted>` |
| 连接串（含账号密码的 JDBC/S3 URL） | 凭据部分 `<redacted>` |

### 结构化日志字段白名单（只允许下列字段）

| 字段 | 示例 | 说明 |
|---|---|---|
| `ts` | 2026-01-01T00:00:00Z | 时间 |
| `level` | INFO | 级别 |
| `event` | S3_UPLOAD_SUCCESS | 事件名（枚举） |
| `taskId` / `fileVersionId` / `fileId` / `directoryId` | 100 / 20 | 串联链路（§57 要求） |
| `customerSpace` / `filePath` | customer-a / order/A.zip | 业务定位 |
| `attemptType` / `attemptNo` | GATEWAY_DELIVER / 3 | 尝试记录 |
| `status` | WAITING_RETRY | 9 状态集合内取值 |
| `durationMs` / `retryCount` / `nextRetryAt` | 231 | 观测 |
| `s3Bucket` / `s3Key` | bucket-a / customer-a/order/A.zip | 对象定位 |
| `gatewayRequestId` | xxx | Gateway 回传 ID（若有） |
| `errorCode` | UNKNOWN_OUTCOME | 错误码枚举 |
| `workerId` / `configVersion` | worker-1 / v12 | 运行信息（**不含凭据**） |

### 允许记录 / 禁止记录 对照表

| 允许记录 | 禁止记录 |
|---|---|
| 任务 ID、状态迁移前后状态、`event` 枚举 | `Authorization` 头全文 |
| `s3Bucket` + `s3Key` | `S3_SECRET_KEY` / `GATEWAY_APP_KEY` |
| 错误码与异常类名（**无凭据的堆栈**） | 数据库密码、连接串中的密码 |
| Gateway 返回码、`gatewayRequestId` | 完整请求 URL 的 query 段 |
| 耗时、重试次数、退避时间 | 完整请求体 / 响应体原文 |
| 文件名与目录（业务要求） | 客户 SFTP 账号密码 |

### 响应体记录策略

| 项 | 策略 |
|---|---|
| 默认 | **不记录**响应体与请求体 |
| 开启条件 | 仅 `DEBUG` 且显式开关 `logging.http.body.enabled=true`，**生产禁用** |
| 截断长度 | 最多 512 字节，超出追加 `…(truncated)` |
| 脱敏正则 | `(?i)(authorization\|appkey\|secret\|password\|token)\s*[:=]\s*[^,;\s"]+` → `$1=<redacted>` |
| 采样 | 最多 1%（或每 100 次 1 次），避免日志放大与敏感数据堆积 |
| 存储 | 不进 PostgreSQL（§55）；日志系统需有保留期与访问控制 |

### 控制措施

| 编号 | 威胁/风险 | 控制措施 | 验证方式 | 责任方 |
|---|---|---|---|---|
| SEC-21 | 请求头泄露 | 关闭 HTTP client 的 header 日志；框架级脱敏拦截器统一处理 | 注入假凭据跑链路，日志 `grep` 无命中 | 开发 |
| SEC-22 | 日志字段失控 | 使用结构化日志 + 白名单字段；禁止拼接任意对象 `toString()` | 代码评审 + 日志字段抽样比对白名单 | 开发 |
| SEC-23 | 日志系统越权访问 | 日志平台按角色授权；生产日志保留期与访问审计 | 权限矩阵核查 | 运维 + 安全 |
| SEC-24 | 生产误开 DEBUG | 生产 `root` 级别固定 `INFO`；`DEBUG` 需变更审批 | 配置审查 | 运维 |

---

## 认证与授权

### 威胁 / 风险

- Gateway 凭据在 URL query 中传递：被日志、代理、浏览器历史记录。
- S3 使用长期 AccessKey：泄露后长期有效，无法按任务收敛。
- manual retry 无鉴权：任意人可触发重投（At-least-once 下会造成重复投递）。
- 越权操作其他客户空间的任务。

### 控制措施

| 编号 | 威胁/风险 | 控制措施 | 验证方式 | 责任方 |
|---|---|---|---|---|
| SEC-25 | Gateway 认证位置不安全 | **待确认（Q3）**：建议走 `Authorization` 头或 HMAC（AppId + 时间戳 + nonce + 签名），**禁止** query string 传 AppKey | 与 Gateway 团队确认契约后，抓包确认凭据不在 URL | 架构 + Gateway 团队 |
| SEC-26 | 重放攻击 | 请求带时间戳 + 随机数，服务端做时间窗（如 ±5 分钟）与 nonce 去重 | 契约测试：重复 nonce 被拒绝 | Gateway 团队 + 开发 |
| SEC-27 | S3 长期凭据 | V1 使用 access key（环境变量注入）；未来若 S3 支持则切换 IAM Role / STS 临时凭据，最小权限仅限目标桶前缀 | 桶策略核查：仅 `s3:PutObject`/`GetObject` 于目标前缀 | 运维 + 安全 |
| SEC-28 | manual retry 无鉴权 | V1 无前端，但 Service/API 层必须鉴权：调用方需持 `${OPERATOR_TOKEN}`（或 OS 级受限入口）；仅允许对 `WAITING_RETRY` 且为 `latest_stable` 的版本执行（T19） | 无凭据调用被拒（401/403）；非 latest 版本被拒并返回 `SUPERSEDED_BY_NEWER_VERSION` | 开发 |
| SEC-29 | 操作不可追溯 | 每次 manual retry 写 `transfer_event`：`operator`、`action=MANUAL_RETRY`、`taskId`、`reason`、`requested_at`、来源标识 | 查询 `transfer_event` 可还原操作人与时间 | 开发 |
| SEC-30 | 越权访问其他客户空间 | 授权范围按 `customer_space.code` 限定；调用方只能操作被授权空间 | 越权用例返回 403 | 开发 + 安全 |

### 【架构问题】V1 manual retry 的鉴权入口未定义

- **问题**：基线 §2 的 T19 存在 `WAITING_RETRY → READY` 人工强制重试，但整体设计 §79 明确 V1 无 Web Admin，§80 的 Admin API 属 V2，未定义 V1 的触发入口与鉴权方式。
- **风险**：若无入口则运维无法解阻塞；若入口裸奔则任何人可制造重复投递（At-least-once 下的实际风险）。
- **建议方案**：V1 提供受鉴权的内部入口（如 CLI/内部 HTTP + `${OPERATOR_TOKEN}`），或仅允许受控数据库运维操作并强制写 `transfer_event`；两者都要求操作留痕。
- **对现有设计的影响**：不新增业务状态、不引入 Admin 前端，仅补充「操作入口 + 鉴权 + 审计」约定。

### 责任方汇总

架构（契约）、开发（鉴权实现）、Gateway 团队（服务端校验）、安全（授权范围审批）。

---

## 数据保护与保留

### 威胁 / 风险

- 保留策略误删非终态任务：导致「不丢文件」被破坏（见【架构问题】A7）。
- S3 无版本控制：对象被覆盖后**不可回滚**，错误内容可能直接投递给客户。
- 备份未加密：备份介质成为整库与元数据的泄露面。
- 系统解析客户文件内容：扩大数据接触面与合规责任。

### 控制措施

| 编号 | 威胁/风险 | 控制措施 | 验证方式 | 责任方 |
|---|---|---|---|---|
| SEC-31 | 保留策略越界 | 业务数据保留 **1 年**；**仅清理终态**（`DELIVERED`/`CANCELLED`）且 `created_at < now() - 1 year`；非终态（含 `WAITING_RETRY`）**永不清理** | 清理任务 SQL 审查；构造非终态老任务，验证未被删除 | 开发 + DBA |
| SEC-32 | 清理顺序破坏 FK | 严格按 `transfer_event → transfer_attempt → transfer_task → file_version → file` 顺序删除 | 清理任务执行后无 FK 报错；行数核对 | 开发 |
| SEC-33 | S3 覆盖不可回滚 | 明确接受：S3 不启用版本控制，同 key 覆盖后旧内容不可恢复；通过「串行守卫 + 宽限期」限制错误版本投递（基线 §3.5）；如需保留历史由客户侧或独立备份承担 | 架构评审确认；S3 版本控制确认为关闭 | 架构 + 运维 |
| SEC-34 | 备份泄露 | 备份必须加密（静态加密 + 传输加密）；备份访问最小授权；恢复演练在隔离环境进行 | 备份配置核查 + 一次恢复演练记录 | 运维 + DBA |
| SEC-35 | 客户数据过度接触 | 系统只做流式搬运，**不解析文件内容**；`fingerprint = SHA-256(relative_path + size + mtime)`，不是内容 hash；不落盘到本地临时文件（流式） | 代码评审确认无内容解析；无临时落盘 | 开发 |
| SEC-36 | 元数据过度采集 | 仅采集投递必需元数据（路径、size、mtime、fingerprint）；不采集客户业务字段 | 表结构审查 | 架构 + DBA |
| SEC-37 | 已投递数据残留 | `DELIVERED` 记录的 S3 对象保留策略与客户约定一致；V1 不做自动删除 S3 对象（无 Archive/Delete，§80 V3） | 桶生命周期策略核查 | 运维 + 客户 |

### 【架构问题】保留策略与「失败任务永不删除」冲突（引用 A7）

- **问题**：1 年保留与「失败任务永不自动删除」在极端长期失败场景下互斥。
- **风险（安全视角）**：若为满足保留期而清理非终态任务，将违反 I3/I7 不变量，构成数据丢失；若永不清理，则元数据（含客户路径）长期留存，扩大数据保护面。
- **建议方案**：维持「仅清理终态」为硬约束；对长期 `WAITING_RETRY` 增加运维告警与人工处置，而非自动删除。
- **对现有设计的影响**：不改状态机；仅影响清理任务的 WHERE 条件与告警策略。

---

## 密钥轮换与泄露响应

### 威胁 / 风险

- 凭据长期不变：泄露窗口无限延长。
- 轮换时任务中断：在途任务因旧凭据失效而批量失败。
- 轮换影响任务快照：若凭据进过快照，轮换后历史任务仍可用旧凭据。
- 泄露后无应急流程：吊销与轮换顺序错误导致污染持续。

### 控制措施

| 编号 | 威胁/风险 | 控制措施 | 验证方式 | 责任方 |
|---|---|---|---|---|
| SEC-38 | 轮换周期缺失 | 建议周期：S3 凭据 90 天、Gateway 凭据 90 天、DB 密码 180 天（**具体周期待确认**）；到期前 14 天告警 | 凭据台账含创建/到期时间；到期告警演练 | 安全 + 运维 |
| SEC-39 | 轮换导致在途失败 | 双密钥过渡：先启用新凭据并保持旧凭据可用 → 灰度切换 → 观察一个完整重试窗口（≥ `supersede.gracePeriod` + 最长退避）→ 吊销旧凭据 | 轮换演练期间 `WAITING_RETRY` 不异常增长 | 运维 |
| SEC-40 | 轮换影响任务快照 | 凭据**不进入**任务快照，`config_version` 只覆盖非敏感配置；因此轮换与在途任务的配置快照**不矛盾**，历史任务无需重放 | 审查快照字段；轮换后历史 `WAITING_RETRY` 任务可正常继续 | 开发 + 架构 |
| SEC-41 | 泄露扩散 | 泄露应急顺序：① 立即吊销泄露凭据 → ② 轮换为新凭据并注入 → ③ 审计 `transfer_attempt`/`transfer_event`/日志定位影响范围 → ④ 评估是否需要重投或作废（终态不可复活，按 T22/T23）→ ⑤ 通知客户与安全负责人 → ⑥ 复盘 | 一次桌面演练产出时间线记录 | 安全 + 运维 |
| SEC-42 | 泄露后无痕迹 | 所有凭据使用点必须有可关联标识（`workerId`、`gatewayRequestId`），以便按时间窗筛选 | 按时间窗查询 attempt 记录 | 开发 |

### 泄露应急步骤（固定顺序）

1. **吊销**：立即在 S3/Gateway/DB 侧吊销泄露凭据，不等轮换完成。
2. **轮换**：生成新凭据并通过 Secret 注入重启；遵循双密钥过渡避免在途任务失败。
3. **审计**：导出泄露时间窗内的 `transfer_attempt`、`transfer_event`、应用日志与 S3 访问日志，确认是否有越权上传/投递。
4. **通知**：按约定通知客户与安全负责人；若涉及客户数据投递异常，附影响范围。
5. **复盘**：更新检查清单与轮换周期，必要时缩短周期。

---

## 威胁建模（STRIDE 简化）

| 类别 | 本系统具体场景 | 控制措施 | 引用 | 残余风险 |
|---|---|---|---|---|
| 欺骗（Spoofing） | 伪造 Gateway 调用方，冒充本系统投递文件 | AppId/AppKey + 时间戳 + nonce 签名；HTTPS 双向身份可后续增强 | SEC-25、SEC-26 | 凭据泄露后仍可被冒充 |
| 欺骗（Spoofing） | 冒充 S3 端点，接收上传的客户文件 | HTTPS + 证书校验，禁止 `trustAllCerts` | SEC-16、SEC-14 | 若 CA 被攻陷 |
| 欺骗（Spoofing） | 伪造运维身份执行 manual retry | `${OPERATOR_TOKEN}` 鉴权 + 操作留痕 | SEC-28、SEC-29 | 内部人员滥用 |
| 篡改（Tampering） | 中间人替换 S3 对象，向客户投递恶意内容 | TLS 全链路 + S3 凭据最小权限 + 桶策略限制写入前缀 | SEC-14、SEC-27 | S3 无版本控制，覆盖不可回滚 |
| 篡改（Tampering） | 篡改 NAS 源文件后再被扫描上传 | SMB 3.1.1 + 签名/加密 + 只读挂载账号 | SEC-18 | NAS 侧权限管理不到位 |
| 篡改（Tampering） | 直接改数据库状态（如把任务改成 `DELIVERED`） | DB 最小权限 + 无 superuser + 连接 TLS + 审计 `transfer_event` | SEC-08、SEC-12 | DBA 高权限内部风险 |
| 否认（Repudiation） | 操作人否认执行过 manual retry | `transfer_event` 记录 operator/action/reason/时间 | SEC-29 | 无强身份（仅 token）时归因有限 |
| 否认（Repudiation） | 客户否认收到文件 | `transfer_attempt` + `DELIVERED` + `gatewayRequestId` 形成证据链 | SEC-42 | Gateway 侧回执依赖对方 |
| 信息泄露（Info Disclosure） | 日志泄露 AppKey/SecretKey/密码 | 脱敏清单 + 字段白名单 + 生产禁 DEBUG | SEC-03、SEC-21–SEC-24 | 日志平台自身被攻陷 |
| 信息泄露（Info Disclosure） | 数据库备份泄露客户文件路径等元数据 | 备份加密 + 最小授权 + 隔离恢复 | SEC-34 | 备份介质丢失 |
| 信息泄露（Info Disclosure） | 凭据落入任务快照 / 配置表 | 快照白名单 + 全表字段审查 | SEC-02、SEC-04 | 新增字段时回归遗漏 |
| 信息泄露（Info Disclosure） | S3 桶权限过宽，非授权者读取对象 | 桶策略最小权限 + 仅目标前缀 + 建议 SSE | SEC-20、SEC-27 | 桶配置漂移 |
| 拒绝服务（DoS） | 大量小文件或超大 backlog 打满 worker/DB | 有界队列 + 背压 + 并发上限（§6）+ 单目录 1 万文件上限 | 基线 §6 | 目录级突发 |
| 拒绝服务（DoS） | Gateway 长期 5xx 导致无限重试占用资源 | 指数退避 + `next_retry_at` + 告警；永不产生 `FAILED` 终态（设计约束） | 基线 §1 | 长期占用 DB 与配额 |
| 拒绝服务（DoS） | 凭据被吊销导致上传全失败 | 双密钥过渡 + 到期告警 + fail-fast 检测 | SEC-38、SEC-39 | 轮换窗口内短时失败 |
| 权限提升（Elevation） | 应用账号为 superuser，SQL 注入即整库接管 | 应用/migration 账号分离 + 无 DDL/superuser | SEC-08、SEC-09 | 依赖 DBA 配置正确 |
| 权限提升（Elevation） | 通过 manual retry 越权操作其他客户空间 | 按 `customer_space` 授权 + 越权 403 | SEC-30 | 授权矩阵维护遗漏 |
| 权限提升（Elevation） | 主机上其他进程读取进程环境中的凭据 | Secret 注入最小化 + 进程隔离 + 文件权限 600 | SEC-05、SEC-06 | root 级主机被攻陷 |

> 说明：以上 18 行，覆盖 STRIDE 六类；「残余风险」为明确接受项，不通过新增组件消除。

---

## 安全检查清单（上线前）

- [ ] SEC-01 仓库内 `git grep` 无真实凭据；CI 扫描通过。
- [ ] SEC-02 全表字段核查：`nas`/`customer_space`/`directory`/`file`/`file_version`/`transfer_task`/`transfer_attempt`/`transfer_event` 无凭据列。
- [ ] SEC-03 注入假凭据跑全链路，日志文件中 `grep -iE 'secret|appkey|password|authorization'` 无明文命中。
- [ ] SEC-04 任务快照字段白名单核查：不含任何凭据字段。
- [ ] SEC-05 部署单元核查：凭据经 Secret/EnvironmentFile（600）注入；`ps -ef` 与 shell history 无凭据。
- [ ] SEC-07 生产 `application.yml` 与启动参数：无 `DEBUG`/`TRACE`，无 HTTP client body 日志。
- [ ] SEC-08 应用账号 `rolsuper=rolcreatedb=rolcreaterole=f`。
- [ ] SEC-09 DDL 由 migration 账号执行；应用运行期无 `CREATE/ALTER/DROP`。
- [ ] SEC-10 应用账号无 `TRUNCATE` 权限。
- [ ] SEC-12 生产 JDBC URL 为 `sslmode=require` 及以上；`pg_stat_ssl.ssl = true`。
- [ ] SEC-14/15 配置中 `S3_ENDPOINT`、`GATEWAY_ENDPOINT` 均为 `https://`，且启动时 fail-fast 校验。
- [ ] SEC-16 代码扫描无 `trustAllCerts`、`X509TrustManager` 空实现、`HostnameVerifier` 恒真。
- [ ] SEC-17 `openssl s_client` 探测：仅 TLS 1.2+ 可用。
- [ ] SEC-18 `mount` 输出确认 SMB `vers=3.1.1`、签名必需；凭据文件权限 600。
- [ ] SEC-21 结构化日志字段全部命中白名单，无自由拼接对象。
- [ ] SEC-25/26 Gateway 凭据不在 URL query；时间戳 + nonce 防重放验证通过。
- [ ] SEC-27 S3 凭据仅可操作目标桶前缀，无 `DeleteBucket`/`ListAllMyBuckets` 等权限。
- [ ] SEC-28 无凭据调用 manual retry 返回 401/403；非 latest 版本返回 `SUPERSEDED_BY_NEWER_VERSION`。
- [ ] SEC-29 每次 manual retry 均有 `transfer_event` 记录（operator/action/reason/时间）。
- [ ] SEC-31 清理任务只删终态且 `created_at < now() - 1 year`；非终态任务未被删除（含构造用例）。
- [ ] SEC-32 清理顺序为 `transfer_event → transfer_attempt → transfer_task → file_version → file`，执行无 FK 报错。
- [ ] SEC-34 备份加密已开启；完成一次隔离环境恢复演练。
- [ ] SEC-35 代码评审确认：不解析文件内容、不本地落盘、`fingerprint` 不含内容 hash。
- [ ] SEC-38 凭据台账含创建/到期时间；到期告警已验证。
- [ ] SEC-39 双密钥轮换演练：轮换期间 `WAITING_RETRY` 未异常增长。
- [ ] SEC-41 泄露应急演练：吊销 → 轮换 → 审计 → 通知，产出时间线记录。

---

## 安全反模式

| 反模式 | 后果 | 正确做法 | 关联 |
|---|---|---|---|
| 明文密码写进 `application.yml` | 凭据随代码进 Git，泄露不可撤回 | 环境变量/Secret 注入 + 占位符 `${S3_SECRET_KEY}` | SEC-01 |
| 凭据写入 PostgreSQL（配置表/任务快照） | DB 备份与只读账号成为泄露面 | 凭据永不落库；快照只含非敏感配置 | SEC-02、SEC-04 |
| 日志打印完整请求头 | `Authorization`/`AppKey` 进日志系统 | 框架级脱敏 + 字段白名单 | SEC-03、SEC-21 |
| 日志打印完整 URL query | 若凭据在 query 中则完全暴露 | 禁止 query 传凭据；query 整体 `<redacted>` | SEC-25 |
| `trustAllCerts` / 关闭 TLS 校验 | 中间人可替换 S3 对象并投递给客户 | 证书校验开启，禁用一切绕过开关 | SEC-16 |
| `HostnameVerifier` 恒真 | 证书主机名校验失效 | 使用默认校验，不自定义 | SEC-16 |
| 共享超级账号 | 一处缺陷导致整库失陷，无法归因 | 应用/migration/监控账号分离，最小权限 | SEC-08、SEC-13 |
| 把密钥写进任务快照 | 历史任务永久携带可复用凭据，轮换失效 | `config_version` 只标识非敏感配置 | SEC-04、SEC-40 |
| 生产开启 HTTP body 日志 | 请求/响应体与凭据外泄、日志放大 | 默认不记录；仅 DEBUG + 截断 + 采样 | 日志脱敏章节 |
| `sslmode=disable` 连数据库 | 同网段嗅探与篡改 | `sslmode=require` 及以上 | SEC-12 |
| 凭据写进 `fstab` 明文 | 主机级泄露点 | `credentials` 文件权限 600 | SEC-18 |
| 用 `docker run -e SECRET=...` | 出现在 shell history 与 `ps` | 使用 Secret/EnvironmentFile | SEC-05 |

---

## 待确认与开放问题（引用基线 §10）

| 编号 | 关联 | 说明 |
|---|---|---|
| Q3 | SEC-25、SEC-26 | Gateway 认证方式与凭据传递位置（header/签名/query）未确认，直接影响日志脱敏与防重放设计。 |
| Q3 | SEC-25 | Gateway 成功响应契约与幂等键未确认，影响「结果未知」判定与重复投递的可观测性。 |
| Q6 | SEC-33 | 是否做上传后校验（size/ETag 比对）未确认；若不校验，S3 覆盖错误更难被发现。 |
| — | SEC-38 | 轮换周期的具体数值（90/180 天）待安全与运维确认。 |
| — | SEC-20 | S3 桶默认加密（SSE-S3/SSE-KMS）是否可用，取决于实际 S3 兼容性。 |
| — | SEC-12 | 本地 Docker 测试库若不支持 TLS，需显式记录为例外，且不得用于生产。 |
