# 05 Exception Matrix（异常矩阵）

## 本部分范围

- 覆盖从 NAS 扫描、稳定判定、S3 上传、Gateway 投递到 `DELIVERED` 全链路上可能出现的异常。
- 按 9 大类别（NAS、DB、S3、Gateway、JVM、Network、Timeout、Duplicate、Crash）逐场景拆解，共 60 个场景（`EX-01` ~ `EX-60`）。
- 每个场景给出 5 个固定列：`检测方式` / `任务状态影响` / `自动动作` / `幂等性要求` / `可观测信号`。
- `任务状态影响` 一律使用 Phase 0 决策基线 §2 的迁移编号（T1–T23）与 §1 的 9 个状态名，逐字一致。
- 额外给出：异常分类树、结果未知统一处理规则、异常到状态机映射总表、明确禁止的异常处理反模式、`【架构问题】` 标注。

## 本部分不做什么

- 不新增任何业务状态：状态集合恰好 9 个（`DISCOVERED`、`STABILITY_CHECK`、`READY`、`UPLOADING`、`S3_UPLOADED`、`GATEWAY_DELIVERING`、`WAITING_RETRY`、`DELIVERED`、`CANCELLED`），**禁止** `FAILED`、`CLAIMED`、`PROCESSING`、`SUPERSEDED`。
- 不写业务代码，不创建 `*.java`、`*.sql`、`pom.xml`、`mvnw`；文中的 SQL 仅为说明语义的示意片段，非 DDL 交付物。
- 不引入 Kafka/RabbitMQ/Redis/Kubernetes/分布式事务/分布式锁/S3 对象版本控制/文件内容 hash。
- 不定义 Gateway 认证方式与幂等键契约（属开放问题 Q3），本文只标注依赖点。
- 不修改已确认架构；与架构冲突处一律用 `【架构问题】` 标注，不擅自改设计。

## 对应整体设计文档章节

| 本文内容 | 整体设计文档章节 |
|---|---|
| NAS 文件不存在 / 消失 / 上传中删除 | §12、§73、§74、§86 |
| 崩溃恢复与 lease | §16、§17、§64、§65、§84、§85 |
| Gateway 超时与结果未知 | §24、§48、§49、§63 |
| 失败不删任务 / 永久重试 | §25、§26、§50 |
| 状态机与迁移集中管理 | §13、§14、§53 |
| S3 上传失败与覆盖 | §40、§41、§75、§76、§77 |
| 数据库事务与唯一约束 | §28、§29、§36、§37、§66、§69 |
| 故障场景矩阵与验收 | §86、§92 |
| Metrics / 日志 / 告警 | §57、§58、§59 |

## 0. 使用约定

- 迁移编号、状态名以 Phase 0 决策基线 §1、§2 为准；本文不再复述矩阵全文。
- `任务状态影响` 中「无迁移」表示本次异常不改变任务业务状态，任务保持原状态，由下一周期/下一轮调度再次处理。
- 结果未知类异常的 `error_code` 统一为 `UNKNOWN_OUTCOME`。
- 所有恢复/取消动作必须为**条件更新**：`UPDATE ... WHERE id=? AND status=<expected_from>`，影响行数 0 即视为并发冲突并放弃本次动作。

---

## 1. NAS 异常（10 场景）

| 场景 | 检测方式 | 任务状态影响 | 自动动作 | 幂等性要求 | 可观测信号 |
|---|---|---|---|---|---|
| EX-01 NAS 不可访问 | Scanner 目录遍历抛 `IOException`，挂载点探测失败 | 无迁移（本周期 T1 未触发，已存在任务状态保持） | 本目录本周期跳过，下周期重扫；连续 N 次失败告警 | 无新写；已落库版本由 `UNIQUE(file_id,fingerprint)` 保证下周期不重复 | metric `scan_failure_total`、`scan_duration_seconds`；log `event=SCAN_FAILURE` |
| EX-02 目录不存在/不可读 | `Files.isReadable=false` 或 `NoSuchFileException` | T5 `STABILITY_CHECK`→`CANCELLED`；T7 `READY`→`CANCELLED`；T21 →`CANCELLED` | 跳过该目录本周期；对未开始读取的任务按取消流程条件更新为 `CANCELLED`（`cancel_reason=DIRECTORY_UNAVAILABLE`） | 条件更新 `WHERE id=? AND status=<expected_from>`，重复取消影响行数 0 | metric `files_cancelled_total`；log `event=DIRECTORY_UNAVAILABLE` |
| EX-03 目录被禁用 | `directory.enabled=false`（配置/DB） | T5 `STABILITY_CHECK`→`CANCELLED`；T7 `READY`→`CANCELLED`；T21 →`CANCELLED` | 条件更新置 `CANCELLED`（`cancel_reason=DIRECTORY_DISABLED`）；Scanner 不再扫描该目录 | 条件更新；终态不可复活（T23 禁止 `CANCELLED`→任意） | log `event=DIRECTORY_DISABLED`；metric `files_cancelled_total` |
| EX-04 文件在稳定前消失 | 第二次扫描时 SMB 中不存在该 path | T5 `STABILITY_CHECK`→`CANCELLED` | 条件更新置 `CANCELLED`（`cancel_reason=FILE_DISAPPEARED`）；不产生新 task | 条件更新 `WHERE status='STABILITY_CHECK'`；重复取消影响行数 0 | metric `files_cancelled_total`；log `event=FILE_VANISHED_PRE_STABLE` |
| EX-05 文件在 READY 后消失 | claim 前存在性校验失败，或读取前 `stat` 失败 | T7 `READY`→`CANCELLED` | 条件更新置 `CANCELLED`（`cancel_reason=FILE_DISAPPEARED`）；不进入 `UPLOADING` | 条件更新 `WHERE status='READY'`；已 `CANCELLED` 不再迁移 | log `event=FILE_VANISHED_PRE_READ`；metric `task_cancelled` |
| EX-06 文件在 UPLOADING 中被删除 | 读取中 SMB 句柄仍有效，但目录项消失 | 不中断，继续至 T8 `UPLOADING`→`S3_UPLOADED`；若读取失败则 T9 `UPLOADING`→`WAITING_RETRY` | 不因 NAS 删除中断已打开的读取（§12）；成功则继续投递，失败按 T9 退避重试 | 同一 S3 Object Key 覆盖；`UNIQUE(file_version_id)` 保证单 task | log `event=FILE_DELETED_DURING_UPLOAD`；metric `upload_success_total`/`upload_failure_total` |
| EX-07 文件内容/大小/mtime 变化 | `fingerprint = SHA-256(relative_path+size+mtime)` 变化 | 稳定期 T3 `STABILITY_CHECK`→`STABILITY_CHECK`（重置基线）；若在 `READY`/`WAITING_RETRY` 之后出现更新稳定版本则 T20/T21 →`CANCELLED` | 更新 `size`/`mtime`/`fingerprint` 重新稳定判定；旧版本由 supersede 条件取消 | `UNIQUE(file_id,fingerprint)` 防重复版本；`UNIQUE(file_id,version_no)`；取消用条件更新 | metric `files_discovered_total`；log `event=FINGERPRINT_CHANGED` |
| EX-08 SMB 读中途断连 | 流读取抛 `IOException`/EOF 异常 | T9 `UPLOADING`→`WAITING_RETRY` | `retry_count+1`、`next_retry_at=backoff(...)`、释放 lease；重传覆盖同一 Object | 覆盖同一 S3 Object Key；`UNIQUE(transfer_task_id,attempt_type,attempt_no)` 防重复 attempt | metric `upload_failure_total`；log `event=SMB_READ_BROKEN` |
| EX-09 权限不足 | 扫描/读取抛 `AccessDeniedException` | 扫描期无迁移（本周期跳过）；读取期 T9 `UPLOADING`→`WAITING_RETRY` | 扫描期跳过并告警；读取期退避重试并告警（通常需人工修权限） | 条件更新 `WHERE status='UPLOADING'`；重复失败不新增 task | log `event=NAS_PERMISSION_DENIED`；metric `scan_failure_total`/`upload_failure_total` |
| EX-10 文件被独占锁 | 打开/读取抛 SMB 共享冲突（`STATUS_SHARING_VIOLATION`） | 读取前保持 `READY`（下轮再试）；读取中 T9 `UPLOADING`→`WAITING_RETRY` | 不 claim 或释放 lease 后退避重试，等下一轮文件可读 | 条件更新；claim 用 `FOR UPDATE SKIP LOCKED` 防并发抢占 | log `event=FILE_LOCKED`；metric `upload_failure_total` |

## 2. DB 异常（9 场景）

| 场景 | 检测方式 | 任务状态影响 | 自动动作 | 幂等性要求 | 可观测信号 |
|---|---|---|---|---|---|
| EX-11 连接池耗尽 | HikariCP 获取连接超时 | 无迁移（claim/写失败保持原状态，如 `READY` 保持 `READY`） | 放弃本周期 claim；队列满停止 claim（背压）；等待连接释放 | claim 未命中不改状态；唯一约束兜底重复 | metric 连接池 pending、`task_ready` 增长；log `event=DB_POOL_EXHAUSTED` |
| EX-12 连接中断 | 事务中抛 `SQLException` 或连接失效 | 事务回滚保持原状态（`UPLOADING` 保持 `UPLOADING`）；由 lease 恢复 T10 `UPLOADING`→`READY` | 回滚当前事务并重连；不假设外部动作成功 | 条件更新 `WHERE id=? AND status=?`；S3 覆盖语义 | log `event=DB_CONNECTION_LOST`；metric stale lease 数量 |
| EX-13 事务回滚 | 提交前异常触发 `rollback` | 无状态变更（原子回滚） | 原子回滚后由下一轮调度重新执行，保持原状态 | 事务原子性 + 条件更新；唯一约束兜底 | log `event=TX_ROLLBACK` |
| EX-14 死锁重试 | PostgreSQL `40P01` deadlock detected | 无迁移（重试事务） | 有限次带退避重试事务；超限则放弃本周期，下轮再试 | 事务重试幂等；条件更新 | log `event=DB_DEADLOCK_RETRY`；metric `db_deadlock_total` |
| EX-15 序列化失败 | PostgreSQL `40001` serialization failure | 无迁移（重试事务） | 重新读取并重试事务；有限次退避 | 条件更新；唯一约束兜底 | log `event=DB_SERIALIZATION_FAILURE` |
| EX-16 唯一约束冲突 | `23505` unique_violation | 无迁移（T1 不重复创建，保持既有记录） | `ON CONFLICT DO NOTHING` 跳过并继续 | `UNIQUE(file_id,fingerprint)`、`UNIQUE(file_version_id)`、`UNIQUE(transfer_task_id,attempt_type,attempt_no)` | log `event=DB_UNIQUE_CONFLICT` |
| EX-17 长事务超时 | `statement_timeout` / `idle_in_transaction_timeout` | 回滚保持原状态 | 回滚、拆分短事务重试；不跨外部 IO 长时间持锁 | 条件更新；唯一约束兜底 | log `event=DB_LONG_TX_TIMEOUT`；metric `db_tx_timeout_total` |
| EX-18 DB 不可用（启动期） | 启动时连接失败 | 无迁移（不启动，无任务状态变更） | 启动失败/重试连接；严格按 DB→Recovery→Worker→Scanner 顺序，不先启动 Scanner | 无副作用；恢复期条件更新可重入 | log `event=STARTUP_DB_UNAVAILABLE`；进程退出码非 0 |
| EX-19 DB 不可用（运行期） | 连接/查询持续失败 | 无迁移；在途任务由 lease 兜底 T10 `UPLOADING`→`READY`、T16 `GATEWAY_DELIVERING`→`WAITING_RETRY` | 停止 claim 与扫描落库，指数退避等待恢复；恢复后按启动顺序重入 | 恢复动作条件更新；唯一约束兜底 | log `event=DB_UNAVAILABLE_RUNTIME`；metric stale lease、`task_ready` |

## 3. S3 异常（8 场景）

| 场景 | 检测方式 | 任务状态影响 | 自动动作 | 幂等性要求 | 可观测信号 |
|---|---|---|---|---|---|
| EX-20 connect timeout | SDK 连接超时异常 | T9 `UPLOADING`→`WAITING_RETRY` | `retry_count+1`、`next_retry_at=backoff(...)`；释放 lease | 覆盖同一 S3 Object Key；attempt 唯一约束 | metric `upload_failure_total`、`upload_duration_seconds`；log `event=S3_CONNECT_TIMEOUT` |
| EX-21 read timeout | SDK 读超时 | T9 `UPLOADING`→`WAITING_RETRY` | 退避重试；重传覆盖 | 覆盖同一 S3 Object Key | log `event=S3_READ_TIMEOUT`；metric `upload_failure_total` |
| EX-22 5xx | HTTP 5xx（含 500/503 SlowDown） | T9 `UPLOADING`→`WAITING_RETRY` | 指数退避重试（可延长退避） | 覆盖同一 S3 Object Key | log `event=S3_SERVER_ERROR`；metric `upload_failure_total` |
| EX-23 4xx（含 403 认证） | HTTP 4xx，403 `AccessDenied`/`SignatureDoesNotMatch` | T9 `UPLOADING`→`WAITING_RETRY`（不删，永久重试） | 退避重试并立即告警（多为凭据/权限配置错误，需人工） | 覆盖同一 S3 Object Key | log `event=S3_AUTH_FAILED`；metric `upload_failure_total` + 告警 |
| EX-24 连接重置 | `IOException: connection reset` | T9 `UPLOADING`→`WAITING_RETRY` | 退避重试、重传覆盖 | 覆盖同一 S3 Object Key | log `event=S3_CONNECTION_RESET` |
| EX-25 对象过大/权限 | 413 `EntityTooLarge` 或对象级 403 | T9 `UPLOADING`→`WAITING_RETRY`（不删，保持重试） | 退避重试并告警（可能为永久性错误，需人工核查） | 覆盖同一 S3 Object Key | log `event=S3_OBJECT_REJECTED`；metric `upload_failure_total` |
| EX-26 上传成功但响应丢失 | PUT 无响应/超时，无法确认结果 | T9 `UPLOADING`→`WAITING_RETRY`（`error_code=UNKNOWN_OUTCOME`） | 按失败处理，退避后重传覆盖同一 Object（§18、§75） | 同 Object Key 覆盖；S3 无版本控制，最终仅一份对象 | log `event=S3_UPLOAD_UNKNOWN_OUTCOME`；metric `upload_failure_total` |
| EX-27 桶不存在 | `NoSuchBucket` | T9 `UPLOADING`→`WAITING_RETRY`（不删，保持重试） | 退避重试并立即告警（配置错误，需人工建桶/改配置） | 覆盖同一 S3 Object Key | log `event=S3_BUCKET_NOT_FOUND`；metric `upload_failure_total` |

## 4. Gateway 异常（9 场景）

| 场景 | 检测方式 | 任务状态影响 | 自动动作 | 幂等性要求 | 可观测信号 |
|---|---|---|---|---|---|
| EX-28 connect timeout | HTTP client 连接超时 | T15 `GATEWAY_DELIVERING`→`WAITING_RETRY`（`error_code=UNKNOWN_OUTCOME`） | 按失败处理，退避后重投；允许重复投递 | At-least-once；attempt 唯一约束；Gateway 幂等键（Q3 待确认） | log `event=GATEWAY_CONNECT_TIMEOUT`；metric `gateway_failure_total`、`gateway_retry_total` |
| EX-29 read timeout | 响应超时 | T15 `GATEWAY_DELIVERING`→`WAITING_RETRY`（`error_code=UNKNOWN_OUTCOME`） | 按失败处理，退避重投（§24） | At-least-once；允许重复投递 | log `event=GATEWAY_READ_TIMEOUT`；metric `gateway_failure_total` |
| EX-30 4xx | Gateway 明确返回 4xx | T14 `GATEWAY_DELIVERING`→`WAITING_RETRY` | `retry_count+1`、`next_retry_at=backoff(...)`；记录 `http_status`/`gateway_request_id` | attempt 唯一约束 | log `event=GATEWAY_CLIENT_ERROR`；metric `gateway_failure_total` |
| EX-31 5xx | Gateway 明确返回 5xx | T14 `GATEWAY_DELIVERING`→`WAITING_RETRY` | 指数退避重试 | attempt 唯一约束 | log `event=GATEWAY_SERVER_ERROR`；metric `gateway_failure_total` |
| EX-32 响应丢失（结果未知） | 连接中断/无响应，无法判断结果 | T15 `GATEWAY_DELIVERING`→`WAITING_RETRY`（`error_code=UNKNOWN_OUTCOME`） | 一律按失败处理并重投（§24） | At-least-once 允许重复；attempt `outcome=UNKNOWN`（见【架构问题】EX-A1） | log `event=GATEWAY_UNKNOWN_OUTCOME`；metric `gateway_failure_total` |
| EX-33 响应慢 | 响应时间接近 `gateway.readTimeout` | 未超时无迁移；超 `gateway.readTimeout` 则 T15 `GATEWAY_DELIVERING`→`WAITING_RETRY` | 预算内等待；超时按失败重投 | At-least-once | metric `gateway_duration_seconds` 高水位；log `event=GATEWAY_SLOW` |
| EX-34 限流 429 | HTTP 429 且可能带 `Retry-After` | T14 `GATEWAY_DELIVERING`→`WAITING_RETRY` | 尊重 `Retry-After`，退避不早于该时间重投 | attempt 唯一约束 | log `event=GATEWAY_RATE_LIMITED`；metric `gateway_failure_total` |
| EX-35 认证失败 | 401/403 或认证错误响应体 | T14 `GATEWAY_DELIVERING`→`WAITING_RETRY`（不删，永久重试） | 退避重试并立即告警（凭据/配置错误，需人工） | attempt 唯一约束 | log `event=GATEWAY_AUTH_FAILED`；metric `gateway_failure_total` + 告警 |
| EX-36 Gateway 成功但客户 SFTP 失败 | Gateway 明确返回失败（如 SFTP 落盘失败） | T14 `GATEWAY_DELIVERING`→`WAITING_RETRY` | 视为明确失败，退避重投；记录 `gateway_request_id` 便于对账 | At-least-once；重复投递可接受 | log `event=GATEWAY_DOWNSTREAM_FAILURE`；metric `gateway_failure_total` |

## 5. JVM 异常（6 场景）

| 场景 | 检测方式 | 任务状态影响 | 自动动作 | 幂等性要求 | 可观测信号 |
|---|---|---|---|---|---|
| EX-37 上传中 kill -9 | 进程非优雅退出；重启后发现 stale lease | T10 `UPLOADING`→`READY` | 启动 Recovery 条件更新 stale lease → `READY`，重新上传覆盖 | 条件更新 `WHERE status='UPLOADING' AND lease_until<now() AND s3_uploaded_at IS NULL`；Object 覆盖 | log `event=RECOVERY_UPLOADING_TO_READY`；metric `stale_lease_recovered_total` |
| EX-38 Gateway 调用中 kill -9 | 重启后 stale lease，状态 `GATEWAY_DELIVERING` | T16 `GATEWAY_DELIVERING`→`WAITING_RETRY` | 恢复置 `WAITING_RETRY`，退避后重投（不猜测上次结果） | 条件更新；At-least-once 允许重复 | log `event=RECOVERY_DELIVERING_TO_RETRY`；metric `stale_lease_recovered_total` |
| EX-39 DB commit 前 kill -9 | 重启后 DB 无成功记录 | `UPLOADING`→T10 `UPLOADING`→`READY`；`GATEWAY_DELIVERING`→T16 `GATEWAY_DELIVERING`→`WAITING_RETRY` | 恢复后重新执行；S3 覆盖重传 | 条件更新；唯一约束兜底 | log `event=RECOVERY_COMMIT_LOST` |
| EX-40 DB commit 后 kill -9 | 重启后状态已持久化（如 `S3_UPLOADED`/`DELIVERED`） | `S3_UPLOADED`→T12 `S3_UPLOADED`→`GATEWAY_DELIVERING`；`DELIVERED` 为终态不动 | 按持久化状态继续（投递/结束），不重复已提交阶段 | 条件更新；终态不可复活（T22/T23） | log `event=RECOVERY_RESUME_FROM_COMMITTED` |
| EX-41 OOM | `OutOfMemoryError` / 进程退出 | lease 过期 → T10 `UPLOADING`→`READY` / T16 `GATEWAY_DELIVERING`→`WAITING_RETRY` | 进程重启 + Recovery 恢复；有界队列与 Semaphore 防再次 OOM | 条件更新；Object 覆盖 | log `event=JVM_OOM`；metric JVM 内存、stale lease |
| EX-42 优雅关闭超时 | SIGTERM 后等待在途 worker 超时 | 强杀 → lease 过期 → T10 / T16 | 停止扫描 → 停止 claim → 等在途（有超时）→ 尽量完成上传 → 释放 lease → 退出；超时强杀由 lease 兜底 | 条件更新；Object 覆盖 | log `event=GRACEFUL_SHUTDOWN_TIMEOUT`；metric `shutdown_timeout_total` |

## 6. Network 异常（4 场景）

| 场景 | 检测方式 | 任务状态影响 | 自动动作 | 幂等性要求 | 可观测信号 |
|---|---|---|---|---|---|
| EX-43 DNS 失败 | `UnknownHostException` | S3 侧 T9 `UPLOADING`→`WAITING_RETRY`；Gateway 侧 T15 `GATEWAY_DELIVERING`→`WAITING_RETRY`；NAS 挂载侧无迁移（本周期跳过） | 退避重试并告警（DNS 解析异常） | Object 覆盖 / At-least-once；attempt 唯一约束 | log `event=NETWORK_DNS_FAILURE`；metric `upload_failure_total`/`gateway_failure_total` |
| EX-44 TLS 握手失败 | `SSLHandshakeException` | S3 侧 T9 `UPLOADING`→`WAITING_RETRY`；Gateway 侧 T15 `GATEWAY_DELIVERING`→`WAITING_RETRY` | 退避重试并告警（证书/协议问题） | Object 覆盖 / At-least-once | log `event=NETWORK_TLS_HANDSHAKE_FAILED` |
| EX-45 链路抖动 | 间歇性超时/连接重置 | T9 `UPLOADING`→`WAITING_RETRY`；或 T14/T15 `GATEWAY_DELIVERING`→`WAITING_RETRY` | 指数退避 + 抖动（jitter）重试 | Object 覆盖 / At-least-once | log `event=NETWORK_FLAPPING`；metric `gateway_retry_total` |
| EX-46 代理/防火墙拦截 | 连接被拒/超时，代理返回 407 或网关超时 | S3 侧 T9 `UPLOADING`→`WAITING_RETRY`；Gateway 侧 T14/T15 `GATEWAY_DELIVERING`→`WAITING_RETRY` | 退避重试并告警（网络策略需人工） | Object 覆盖 / At-least-once | log `event=NETWORK_BLOCKED_BY_PROXY` |

## 7. Timeout 异常（5 场景）

| 场景 | 检测方式 | 任务状态影响 | 自动动作 | 幂等性要求 | 可观测信号 |
|---|---|---|---|---|---|
| EX-47 扫描超时预算 | 单目录扫描超过 `scan.timeout` | 无迁移（本周期中止，已落库状态保持） | 中止本目录扫描，下周期重扫；已发现任务不受影响 | `UNIQUE(file_id,fingerprint)`、`UNIQUE(file_version_id)` 防重复 | metric `scan_duration_seconds`；log `event=SCAN_TIMEOUT` |
| EX-48 S3 超时预算 | 超过 `s3.connectTimeout`/`s3.readTimeout` | T9 `UPLOADING`→`WAITING_RETRY` | 退避重试；重传覆盖 | 覆盖同一 S3 Object Key | log `event=S3_TIMEOUT`；metric `upload_duration_seconds` |
| EX-49 Gateway connect 超时 | 超过 `gateway.connectTimeout` | T15 `GATEWAY_DELIVERING`→`WAITING_RETRY`（`error_code=UNKNOWN_OUTCOME`） | 按失败处理，退避重投 | At-least-once | log `event=GATEWAY_CONNECT_TIMEOUT` |
| EX-50 Gateway read 超时 | 超过 `gateway.readTimeout` | T15 `GATEWAY_DELIVERING`→`WAITING_RETRY`（`error_code=UNKNOWN_OUTCOME`） | 按失败处理，退避重投；supersede 宽限期 ≥ `readTimeout + lease.duration + 5m` | At-least-once | log `event=GATEWAY_READ_TIMEOUT` |
| EX-51 lease 续租失败 | 续租 UPDATE 影响行数 0 或超时 | 无直接迁移；执行中任务停止动作，lease 过期后 T10 `UPLOADING`→`READY` / T16 `GATEWAY_DELIVERING`→`WAITING_RETRY` | 立即停止当前外部动作，放弃本次执行，等待恢复流程接管 | 条件更新 `WHERE id=? AND status=? AND worker_id=?` | log `event=LEASE_RENEW_FAILED`；metric `lease_renew_failure_total` |

## 8. Duplicate（重复）异常（5 场景）

| 场景 | 检测方式 | 任务状态影响 | 自动动作 | 幂等性要求 | 可观测信号 |
|---|---|---|---|---|---|
| EX-52 重复扫描 | 同一/多个周期重复发现同一 `file_version` | 无迁移（T1 幂等，不重复创建） | `ON CONFLICT DO NOTHING` 跳过 | `UNIQUE(file_id,fingerprint)`、`UNIQUE(nas_id,directory_id,relative_path)` | metric `files_discovered_total`；log `event=SCAN_DUPLICATE_SKIPPED` |
| EX-53 重复上传覆盖 | 同一 S3 Object Key 二次 PUT | T8 `UPLOADING`→`S3_UPLOADED` 幂等 | 允许覆盖，最终仅一份对象 | S3 Object Key 不含版本且无版本控制 → 覆盖语义 | log `event=S3_UPLOAD_OVERWRITE` |
| EX-54 重复 Gateway 投递 | 客户/网关收到同一文件多次 | T13 `GATEWAY_DELIVERING`→`DELIVERED` 或 T14/T15 →`WAITING_RETRY` 幂等 | At-least-once 允许重复，最终一致 | Gateway 侧幂等键（Q3 待确认）；attempt 唯一约束 | metric `gateway_request_total`；log `event=GATEWAY_DUPLICATE_DELIVERY` |
| EX-55 重复 attempt 记录 | 同一 `attempt_no` 重复写入 | 无迁移 | `ON CONFLICT DO NOTHING` 跳过 | `UNIQUE(transfer_task_id, attempt_type, attempt_no)` | log `event=ATTEMPT_DUPLICATE_SKIPPED` |
| EX-56 多 worker 抢同一任务 | claim 影响行数 0 / `SKIP LOCKED` | T6 `READY`→`UPLOADING` 未命中 → 保持 `READY` | 放弃本次 claim，下一轮再试 | 条件更新 `WHERE id=? AND status='READY'`；`FOR UPDATE SKIP LOCKED` | log `event=CLAIM_CONTENDED`；metric `task_ready` |

## 9. Crash（崩溃）异常（4 场景）

| 场景 | 检测方式 | 任务状态影响 | 自动动作 | 幂等性要求 | 可观测信号 |
|---|---|---|---|---|---|
| EX-57 Scanner 扫描中途崩溃 | 进程退出，扫描未完成 | 无迁移（已落库保留，T1 幂等） | 重启后重新扫描，唯一约束去重，剩余文件继续发现（无需 checkpoint） | `UNIQUE(file_id,fingerprint)`、`UNIQUE(file_version_id)` | log `event=SCANNER_CRASH_RESUME`；metric `files_discovered_total` |
| EX-58 Worker 崩溃 | stale lease | T10 `UPLOADING`→`READY` / T16 `GATEWAY_DELIVERING`→`WAITING_RETRY` | Recovery 条件更新接管 | 条件更新；Object 覆盖；At-least-once | log `event=WORKER_CRASH_RECOVERED`；metric `stale_lease_recovered_total` |
| EX-59 启动期恢复中断 | Recovery 过程中再次崩溃 | 无迁移（未完成部分保持原状态） | 恢复幂等可重入，下次启动继续处理剩余 stale lease | 条件更新保证重复恢复不覆盖活跃 worker | log `event=RECOVERY_INTERRUPTED`；metric stale lease |
| EX-60 lease 过期未续租 | `lease_until < now()` 且状态为执行阶段 | T10 `UPLOADING`→`READY` / T16 `GATEWAY_DELIVERING`→`WAITING_RETRY` | 恢复流程接管；条件更新避免抢占活跃 worker | 条件更新 `WHERE lease_until<now()` | log `event=LEASE_EXPIRED_RECOVERY`；metric stale lease |

---

## 异常分类树

```text
异常
├── 可重试（Retryable）
│   ├── 瞬时网络/服务端：S3 5xx、连接重置、链路抖动、Gateway 5xx、DB 死锁/序列化失败
│   └── 默认策略：指数退避（initialDelay 30s / multiplier 2 / maxDelay 1h / jitter 20%）重试，
│       任务进入或保持 WAITING_RETRY，永不自动删除，达到任意 retry_count 仍继续重试
├── 不可重试（需人工，Non-retryable / Manual）
│   ├── 配置/凭据/权限类：S3 403/桶不存在/对象过大、Gateway 401/403、NAS 权限不足
│   └── 默认策略：仍然退避重试并立即告警，保持 WAITING_RETRY（系统无 FAILED 终态，
│       禁止失败即删）；人工修复配置/凭据后，下一轮重试自动恢复
└── 结果未知（Unknown Outcome）
    ├── 超时/响应丢失：S3 上传成功但响应丢失、Gateway timeout/响应丢失、lease 续租失败
    └── 默认策略：一律按失败处理并重试，error_code=UNKNOWN_OUTCOME，允许重复上传/投递
```

| 分类 | 典型场景 | 默认处理策略 | 是否可能自动恢复 |
|---|---|---|---|
| 可重试 | EX-08、EX-14、EX-15、EX-20~EX-22、EX-24、EX-28、EX-29、EX-31、EX-45、EX-48 | 指数退避重试，保持 `WAITING_RETRY` | 是 |
| 不可重试（需人工） | EX-09、EX-23、EX-25、EX-27、EX-35、EX-46 | 退避重试 + 立即告警，保持 `WAITING_RETRY`，等人工修复 | 修复后是 |
| 结果未知 | EX-26、EX-32、EX-49、EX-50、EX-51 | 按失败处理并重试，`error_code=UNKNOWN_OUTCOME` | 是 |

## 结果未知（Unknown Outcome）的统一处理规则

**结论：超时/响应丢失一律按失败处理并重试，绝不因为"可能已经成功"而跳过重试。**

规则：

1. 只要无法从外部系统获得**明确成功**响应（含 connect timeout、read timeout、连接中断、响应丢失），即判定为结果未知。
2. 结果未知一律映射为失败：S3 侧 T9 `UPLOADING`→`WAITING_RETRY`；Gateway 侧 T15 `GATEWAY_DELIVERING`→`WAITING_RETRY`。
3. 统一记录 `error_code=UNKNOWN_OUTCOME`，并在 `transfer_attempt` 中标记结果未知（见【架构问题】EX-A1）。
4. 重试时允许重复上传/重复投递，由覆盖语义（同一 S3 Object Key）与 At-least-once 语义兜底。

为什么这样做：

- 超时无法区分「请求未到达」与「请求已执行但响应丢失」。若选择"假定成功"，一旦实际失败就会**丢失文件**，违反「不允许丢失」的硬约束；若选择"假定失败"，最坏代价只是重复一次，而重复已被业务明确接受。
- 事实来源是 PostgreSQL：只有 `attempt(outcome=SUCCESS)` + 状态提交才算成功。没有持久化成功证据就不得推进到 `DELIVERED`。
- 与 §24「At-least-once + 允许重复投递」、§7 崩溃恢复「不猜测上次是否成功，直接重投」一致。

记录方式（示意，非 DDL 交付物）：

```sql
-- Gateway 结果未知：T15
UPDATE transfer_task
   SET status = 'WAITING_RETRY',
       retry_count = retry_count + 1,
       next_retry_at = :backoff,
       last_attempt_finished_at = now()
 WHERE id = :taskId
   AND status = 'GATEWAY_DELIVERING';   -- 条件更新，影响行数 0 即放弃

INSERT INTO transfer_attempt(transfer_task_id, attempt_type, attempt_no, outcome, error_code)
VALUES (:taskId, 'GATEWAY_DELIVER', :attemptNo, 'UNKNOWN', 'UNKNOWN_OUTCOME');
```

## 异常 → 状态机映射总表

| 异常类别 | 触发迁移编号 | 是否终态 |
|---|---|---|
| NAS 稳定前消失 / 目录禁用 | T5 `STABILITY_CHECK`→`CANCELLED` | 是（`CANCELLED`） |
| NAS 读取前消失 | T7 `READY`→`CANCELLED` | 是（`CANCELLED`） |
| NAS/配置统一 supersede 取消 | T21 →`CANCELLED` | 是（`CANCELLED`） |
| 稳定期 fingerprint 变化 | T3 `STABILITY_CHECK`→`STABILITY_CHECK` | 否 |
| S3 失败/超时/网络/认证/结果未知 | T9 `UPLOADING`→`WAITING_RETRY` | 否 |
| S3 成功且 DB 提交成功 | T8 `UPLOADING`→`S3_UPLOADED` | 否 |
| Gateway 明确失败（4xx/5xx/429/认证/下游失败） | T14 `GATEWAY_DELIVERING`→`WAITING_RETRY` | 否 |
| Gateway 超时/响应丢失/结果未知 | T15 `GATEWAY_DELIVERING`→`WAITING_RETRY` | 否 |
| Gateway 明确成功 | T13 `GATEWAY_DELIVERING`→`DELIVERED` | 是（`DELIVERED`） |
| 崩溃恢复：上传中且未提交 | T10 `UPLOADING`→`READY` | 否 |
| 崩溃恢复：S3 已提交 | T12 `S3_UPLOADED`→`GATEWAY_DELIVERING` | 否 |
| 崩溃恢复：投递中 | T16 `GATEWAY_DELIVERING`→`WAITING_RETRY` | 否 |
| 重试到期：S3 已上传 | T17 `WAITING_RETRY`→`GATEWAY_DELIVERING` | 否 |
| 重试到期：S3 未上传 | T18 `WAITING_RETRY`→`UPLOADING` | 否 |
| supersede 取消旧版本 | T20 `WAITING_RETRY`→`CANCELLED`；T21 →`CANCELLED` | 是（`CANCELLED`） |
| 人工强制重试 | T19 `WAITING_RETRY`→`READY` | 否 |
| 非法迁移（如 `DELIVERED`→任意、`CANCELLED`→任意） | 状态机拒绝并告警，无迁移 | 否（保持原状态） |

## 明确禁止的异常处理反模式

1. **吞异常**：捕获后既不重试也不记录、也不改变状态，任务静默停滞。禁止。
2. **`catch (Exception e) {}`**：空 catch 块，异常信息彻底丢失，无法审计与告警。禁止。
3. **无超时的外部 IO**：S3/Gateway/DB/NAS 调用必须设置 connect 与 read 超时；无超时会把线程和 lease 永久占用，违反 §6 有界并发。禁止。
4. **无界重试无退避**：失败后立刻无限重试会打爆下游、放大故障。必须指数退避 + jitter（§26）。禁止。
5. **失败即删任务**：失败后删除 `transfer_task`/`transfer_attempt`/S3 对象即等于丢文件，违反「不允许丢失」与 §50。禁止。
6. **把 `FAILED` 当终态**：状态集合恰好 9 个，失败后必须保持 `WAITING_RETRY` 永久重试；禁止新增 `FAILED`。禁止。
7. **把结果未知当成功**：没有明确成功响应就推进到 `DELIVERED` 会丢文件。禁止（见上节规则）。
8. **恢复时不加条件更新**：无条件 `UPDATE` 覆盖正在执行的 worker，会造成状态错乱。必须 `WHERE id=? AND status=? AND lease_until<now()`。禁止。
9. **在长事务中做外部 IO 并持锁**：会触发长事务超时与连接池耗尽。禁止（§66）。
10. **用异常控制正常流程**：以异常代替状态判断（如靠异常跳出稳定判定）会掩盖真实故障。禁止。

## 【架构问题】

### 【架构问题】EX-A1：`transfer_attempt` 无法表达「结果未知」

- **问题**：`transfer_attempt.success BOOLEAN`（基线 §4.4 / 整体设计 §29）只能表达成功/失败，无法区分"明确失败"与"结果未知"。而本矩阵要求结果未知统一按失败处理并记录 `error_code=UNKNOWN_OUTCOME`。
- **风险**：若结果未知被压平为 `success=false`，则审计与对账无法区分「确定未投递」与「可能已投递」，运维难以判断是否需要与客户核对重复投递。
- **建议方案**：将 `success BOOLEAN` 扩展为 `outcome` 枚举（`SUCCESS`/`FAILURE`/`UNKNOWN`），或新增 `outcome` 列并保留 `success` 作为派生字段。与基线【架构问题】A4 一致。
- **对现有设计的影响**：仅影响 `transfer_attempt` 列定义与 attempt 写入逻辑；不改变状态集合与迁移矩阵。

### 【架构问题】EX-A2：永久性错误与「禁止 FAILED、永久 WAITING_RETRY」冲突

- **问题**：S3 桶不存在/403/对象过大、Gateway 401/403 等属永久性错误（配置或权限问题），系统既无 `FAILED` 终态、又要求永久重试，因此这些任务会永远停留在 `WAITING_RETRY`。
- **风险**：① 无效重试持续消耗 S3/Gateway 配额与日志；② 因串行守卫（基线 §3.2），永久失败的旧版本会阻塞同一 `file_id` 新版本的执行，唯一解阻塞途径是 supersede 将其置为 `CANCELLED`，可能使新版本内容被误取消/延迟。
- **建议方案**：不新增业务状态；增加「永久失败」标记（如 `permanent_error BOOLEAN` 或 `error_class=PERMANENT`）+ 立即告警 + 运维干预通道；同时明确此类任务的 supersede 优先级（例如允许在人工确认后由 supersede 取消旧版本）。是否放宽「永久重试」需业务确认。
- **对现有设计的影响**：需在 `transfer_task`/`transfer_attempt` 增加错误分类字段，并调整告警规则；不影响状态集合与迁移矩阵。

### 【架构问题】EX-A3：429 限流与固定指数退避缺乏联动

- **问题**：Gateway 返回 429 时按 T14 进入 `WAITING_RETRY`，但 §26 的固定指数退避不感知 `Retry-After`。
- **风险**：退避时间短于下游要求，可能持续触发限流，形成"重试风暴"，并拖慢其他客户空间的正常投递。
- **建议方案**：在计算 `next_retry_at` 时，若响应含 `Retry-After`，取其与 backoff 的较大值（`next_retry_at = max(backoff, now()+Retry-After)`）。
- **对现有设计的影响**：仅影响 `retry` 模块的退避计算；不改变状态集合与迁移矩阵。
