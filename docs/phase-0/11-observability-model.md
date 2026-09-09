# 11 Observability Model（可观测性模型）

> 事实源：本文件所有状态名、迁移编号（T1–T23）、表名/字段名、术语均以《Phase 0 决策基线》（下称"基线"）§1、§4、§6、§7、§9 为准。
> 与《NAS → S3 → B2B Gateway → SFTP 中转系统整体技术设计》（下称"整体设计"）冲突处，一律以基线为准并标注 `【架构问题】`。

## 本部分范围

本文件定义 NAS → S3 → B2B Gateway → 客户 SFTP 中转系统在**单机 V1** 下"可被运维看见"的全部约定：

1. **指标清单（Metrics）**：指标名、类型、标签、含义、采集点、告警建议，覆盖基线 §58 与提示词要求的全部指标，并补充上传字节/耗时、Gateway HTTP 状态分布、supersede 次数、attempt 耗时。
2. **结构化日志规范**：必需字段、JSON 示例（脱敏）、事件命名规范、级别约定。
3. **追踪（Trace / Correlation）**：`request_id` / `gateway_request_id` 的生成与透传规则、跨 attempt 关联、从 NAS path 串到 attempt 的完整链路。
4. **健康检查**：liveness / readiness / 依赖健康（DB、NAS 目录可读性、S3、Gateway）的检查项与判定阈值，以及"依赖不健康时是否摘流"的明确结论。
5. **告警清单**：告警名、触发条件（含阈值与持续时间）、级别、处置动作、对应 Runbook 小节。
6. **运维查询配方**：可直接执行的查询方案（示意 SQL，非 DDL 交付物），回答 7 个高频运维问题。
7. **Dashboard 建议**：吞吐、积压、失败率、延迟等面板的图表与关键指标。
8. **可观测性不变量**：系统必须始终成立的观测性约束。
9. 显式回答 **"这个文件为什么还没到客户？"** 的完整排查路径（从 `filePath` 出发）。

## 本部分不做什么

- **不引入新状态**：不新增 `CLAIMED`/`PROCESSING`/`FAILED`/`SUPERSEDED`/`DELIVERING` 等任何业务状态；supersede 复用 `CANCELLED` + `cancel_reason=SUPERSEDED_BY_NEWER_VERSION`。
- **不把应用日志写进 PostgreSQL**（整体设计 §55）；日志走 stdout → 采集 → Loki/ELK/Splunk。
- **不引入 Kafka/RabbitMQ/Redis/Kubernetes/分布式事务/分布式锁**；可观测性栈不在 V1 引入清单内，默认使用 Micrometer + 标准日志 + 现有采集器。
- **不定义具体埋点代码**（不写 `*.java`）；不交付 DDL、`pom.xml`、`mvnw`。
- **不定义具体采集后端产品**（Prometheus/Loki/ELK 任选，只要满足本文件的指标/日志契约）。
- **不修改已确认架构**；发现冲突只标注 `【架构问题】`。
- **不把高基数字段作为指标标签**（见 `【架构问题】O1`）。
- **不做自动摘流**（单机 V1，见"健康检查"结论）。

## 对应整体设计文档章节

| 本文件小节 | 整体设计章节 | 说明 |
|---|---|---|
| 指标清单（Metrics） | §58 Metrics | 指标集合与命名基线 |
| 结构化日志规范 | §57 日志设计 | 必需字段与事件 |
| 追踪（Trace / Correlation） | §52 Audit、§57 日志设计 | taskId 串联、Gateway request id |
| 健康检查 | §84 系统启动恢复、§86 故障场景矩阵 | 依赖校验与启动顺序 |
| 告警清单 | §59 最重要的告警 | 6 类基础告警 + 死寂/超时/依赖告警 |
| 运维查询配方 | §60 运维查询模型、§82 推荐索引、§83 不过度索引 | 查询与索引支撑 |
| Dashboard 建议 | §58 Metrics、§60 运维查询模型 | 面板聚合 |
| 可观测性不变量 | §52 Audit、§53 状态迁移集中管理 | 审计与迁移一致性 |
| 核心问题排查路径 | §60 运维查询模型 | "文件为什么还没到客户" |

---

## 指标清单（Metrics）

### 命名与标签约定

- 命名：`<子系统>_<对象>_<单位/含义>`，counter 以 `_total` 结尾，histogram 以 `_seconds` / `_bytes` 结尾，gauge 不加后缀。
- 类型：`counter`（单调递增）、`gauge`（可增可减的瞬时值）、`histogram`（分布，导出 `_bucket`/`_count`/`_sum`）。
- **标签必须是低基数字段**：`nas`、`directory_id`、`customer_space`、`status`、`stage`、`outcome`、`attempt_type`、`error_code`、`error_type`、`status_code`、`dependency`。
- **禁止作为指标标签**：`taskId`、`fileVersionId`、`fileId`、`filePath`、`fingerprint`、`requestId`、`gateway_request_id`。这些字段只进结构化日志与数据库查询（见 `【架构问题】O1`）。
- `task_*` 系列 gauge 由定时采样 `transfer_task` 表得出，采样周期建议 15s～30s；数据库是唯一事实源（基线 §0）。

### 指标表

| 指标名 | 类型(counter/gauge/histogram) | 标签 | 含义 | 采集点 | 告警建议 |
|---|---|---|---|---|---|
| `scanner_scan_total` | counter | `nas`, `directory_id`, `outcome` | 扫描周期执行总次数（scan 次数） | `scanner` 每周期开始/结束 | 周期内无增长 → 扫描停摆（RB-03） |
| `scanner_scan_success_total` | counter | `nas`, `directory_id` | 扫描成功次数 | 扫描完成且无异常 | 成功率 < 95% 持续 30m |
| `scanner_scan_failure_total` | counter | `nas`, `directory_id`, `error_type` | 扫描失败次数（NAS 不可读 / IO 错误等） | 捕获异常时 | 同目录连续 ≥3 次失败 → 告警（RB-03） |
| `scanner_scan_duration_seconds` | histogram | `nas`, `directory_id`, `outcome` | 单次扫描耗时 | 扫描开始/结束 | p99 > 2×扫描周期或 > 10m |
| `scanner_files_discovered_total` | counter | `nas`, `directory_id`, `customer_space` | 新发现文件版本数（discovered files） | 创建 `file_version` 成功后（T1） | 骤降/骤升对比 7 日基线 |
| `scanner_files_stable_total` | counter | `nas`, `directory_id`, `customer_space` | 通过稳定判定进入 `READY` 的版本数（stable files） | T4 迁移成功 | `discovered > 0` 且长期为 0 → 稳定判定异常 |
| `scanner_files_cancelled_total` | counter | `reason` | 稳定前被取消的版本数 | T5 迁移 | 非 supersede 原因突增 |
| `task_ready` | gauge | `customer_space` | 当前 `READY` 任务数（backlog 一部分） | 定时采样 DB count | 持续增长 → backlog 告警（RB-04） |
| `task_waiting_retry` | gauge | `customer_space` | 当前 `WAITING_RETRY` 任务数（backlog 一部分） | 定时采样 DB count | 持续增长或最老年龄超阈值（RB-01） |
| `task_backlog_total` | gauge | — | backlog = `READY` + `WAITING_RETRY` | 派生（两个 gauge 之和） | 1h 增长 > 20% 且最老 > 30m → 告警（RB-04） |
| `task_uploading` | gauge | — | 当前处于 `UPLOADING` 的任务数（uploading 中任务数） | 定时采样 DB count | 长期 > `worker.upload.maxConcurrency` → 卡死/lease 异常 |
| `task_delivering` | gauge | — | 当前处于 `GATEWAY_DELIVERING` 的任务数（gateway delivering 中任务数） | 定时采样 DB count | 长期 > `worker.gateway.maxConcurrency` → 卡死/lease 异常 |
| `task_delivered_total` | counter | `customer_space`, `directory_id` | 成功投递到客户 SFTP 的任务数（DELIVERED） | T13 迁移提交后 | 业务时段 2h 无增长 → 死寂告警（RB-07） |
| `task_cancelled_total` | counter | `customer_space`, `reason` | 终态 `CANCELLED` 任务数（`cancel_reason` 区分消失/目录禁用/supersede） | T5/T7/T20/T21 迁移后 | 非 supersede 原因突增 |
| `task_superseded_total` | counter | `customer_space` | supersede 次数（`cancel_reason=SUPERSEDED_BY_NEWER_VERSION`） | supersede 写入后 | 超历史基线 5× 持续 1h → 告警（RB-08） |
| `task_oldest_age_seconds` | gauge | `status`（`READY`/`WAITING_RETRY`） | 最老非终态任务年龄（backlog 年龄） | 定时采样 `now() - min(created_at)` | `WAITING_RETRY` > 6h → 告警（RB-01） |
| `task_stale_lease` | gauge | `status` | lease 已过期的**有主**活跃执行状态任务数（stale lease 数量）；无主 `GATEWAY_DELIVERING`（T12 恢复待认领）不计入 | 定时采样 `worker_id IS NOT NULL AND lease_until < now()` | > 20 持续 10m 或 > 0 持续 30m → 告警（RB-05） |
| `task_retry_total` | counter | `stage`（upload/gateway）, `error_code` | 任务重试计数累计（retry count） | 每次进入 `WAITING_RETRY` | 单任务 `retry_count` 超阈值 → 长时未完成（RB-01） |
| `retry_wait_seconds` | histogram | `stage`, `error_code` | 两次 attempt 之间的等待时长（retry duration） | 计算 `next_retry_at - COALESCE(last_attempt_finished_at, updated_at)` | p95 超退避上限 2× → 退避配置异常 |
| `attempt_total` | counter | `attempt_type`（`S3_UPLOAD`/`GATEWAY_DELIVER`）, `outcome`（SUCCESS/FAILURE/UNKNOWN） | attempt 次数 | `transfer_attempt` 落库时 | `outcome=UNKNOWN` 占比 > 10% → 告警（RB-02） |
| `attempt_duration_seconds` | histogram | `attempt_type`, `outcome` | 单次 attempt 耗时 | attempt 开始/结束 | p99 超 readTimeout/上传超时 → 告警 |
| `upload_total` | counter | `customer_space`, `directory_id`, `outcome` | S3 上传尝试总次数 | `storage` 调用前后 | 失败率分母 |
| `upload_success_total` | counter | `customer_space` | S3 PUT 成功次数 | T8 提交后 | 成功率跌破基线 |
| `upload_failure_total` | counter | `customer_space`, `error_type` | S3 上传失败次数（S3 failure） | T9 迁移 | 失败率 > 20% 持续 15m → 告警（RB-06） |
| `upload_duration_seconds` | histogram | `outcome` | 单次上传耗时 | 上传开始/结束 | p95 > 文件大小对应预期 2× |
| `upload_bytes_total` | counter | `customer_space` | 上传字节数 | 流式拷贝累计 | 与 NAS 扫描到的 size 总量对账 |
| `gateway_request_total` | counter | `customer_space`, `endpoint` | Gateway 调用总次数 | `gateway` 调用前 | 失败率分母 |
| `gateway_success_total` | counter | `customer_space` | Gateway 明确返回成功的次数 | T13 提交前 | 成功率跌破基线 |
| `gateway_failure_total` | counter | `customer_space`, `error_type` | Gateway 明确失败次数（Gateway failure） | T14 迁移 | 某 customer 连续 10 次失败 → 告警（RB-02） |
| `gateway_http_status_total` | counter | `customer_space`, `status_code`（2xx/4xx/5xx） | Gateway HTTP 状态分布 | 收到响应后 | 5xx 速率 > 10% 持续 10m → 告警（RB-02） |
| `gateway_duration_seconds` | histogram | `customer_space`, `outcome` | Gateway 调用耗时 | 调用开始/结束 | p99 接近 readTimeout → 超时风险 |
| `gateway_retry_total` | counter | `customer_space`, `error_code` | Gateway 维度重试次数 | T17 迁移 | 与 `task_retry_total{stage="gateway"}` 口径一致 |
| `gateway_timeout_total` | counter | `customer_space` | Gateway 超时/连接中断/结果未知次数 | T15 迁移 | 占比 > 10% 持续 15m → 告警（RB-02） |
| `recovery_stale_lease_recovered_total` | counter | `from_status`, `to_status` | 崩溃恢复处理的 stale lease 任务数 | Recovery 条件更新成功（T10/T12/T16） | 突增 → 进程崩溃或被强杀（RB-05） |
| `health_dependency_status` | gauge | `dependency`（db/nas/s3/gateway） | 依赖健康 1=健康 0=不健康 | 健康探测器 | == 0 持续 15m → 告警（RB-09，不摘流） |

### 指标口径要点

- `task_backlog_total` 只统计 `READY` 与 `WAITING_RETRY`（基线 §9 backlog 定义），**不含** `UPLOADING`/`S3_UPLOADED`/`GATEWAY_DELIVERING`（这些是"在途"，用 `task_uploading`/`task_delivering` 观察）。
- `task_oldest_age_seconds` 用 `created_at` 而非 `updated_at`，才能真实反映"客户等了多久"。
- 所有 gauge 的采样必须来自数据库查询，**不得**用 JVM 内存计数推断，否则崩溃恢复后口径漂移。
- 对账不变量：`task_delivered_total` 的增量趋势应与 DB 中 `status='DELIVERED'` 的增量一致（允许采集延迟），差异持续存在说明埋点或迁移提交有 bug。

---

## 结构化日志规范

### 必需字段（一个不能少）

| 字段 | 类型 | 说明 | 来源 |
|---|---|---|---|
| `taskId` | long | 任务主键 `transfer_task.id` | claim / 迁移上下文 |
| `fileVersionId` | long | 版本主键 `file_version.id` | 任务关联 |
| `fileId` | long | 逻辑文件主键 `file.id` | 任务关联（建议冗余列，见 `【架构问题】O3`） |
| `directoryId` | long | 目录主键 `directory.id` | 目录配置 |
| `customerSpace` | string | 客户空间 `customer_space.code` | 目录 → 客户空间 |
| `filePath` | string | 逻辑相对路径 `file.relative_path`（NAS 侧路径语义见 `【架构问题】O6`） | file |
| `event` | string | 事件名（见下方事件规范） | 调用点 |
| `workerId` | string | 处理该任务的 worker 标识（`transfer_task.worker_id`） | claim / 续租 |
| `attemptNo` | int | 本次 attempt 序号（`transfer_attempt.attempt_no`） | attempt 上下文 |
| `requestId` | string | 本次逻辑请求/扫描周期的关联 id（见"追踪"） | 入口生成 |
| `durationMs` | long | 本次动作耗时（毫秒） | 动作开始/结束 |

> `taskId`、`fileVersionId`、`fileId`、`directoryId`、`customerSpace`、`filePath` 六项必须通过 MDC 注入到**每一条**业务日志，缺任意一项即视为日志缺陷（见"可观测性不变量"）。

### JSON 日志示例（脱敏）

```json
{
  "timestamp": "2026-09-09T08:15:22.341+08:00",
  "level": "INFO",
  "logger": "com.example.transfer.gateway.GatewayClient",
  "event": "GATEWAY_SUCCESS",
  "requestId": "req-7f3c2a91",
  "taskId": 100,
  "fileVersionId": 20,
  "fileId": 15,
  "directoryId": 3,
  "customerSpace": "customer-a",
  "filePath": "order/2026/09/A.zip",
  "workerId": "worker-1",
  "attemptNo": 2,
  "gatewayRequestId": "gw-5b8e1d40",
  "httpStatus": 200,
  "durationMs": 231,
  "message": "gateway delivery succeeded"
}
```

```json
{
  "timestamp": "2026-09-09T08:14:01.009+08:00",
  "level": "WARN",
  "event": "GATEWAY_FAILURE",
  "requestId": "req-7f3c2a91",
  "taskId": 100,
  "fileVersionId": 20,
  "fileId": 15,
  "directoryId": 3,
  "customerSpace": "customer-a",
  "filePath": "order/2026/09/A.zip",
  "workerId": "worker-1",
  "attemptNo": 1,
  "gatewayRequestId": "gw-1a2b3c4d",
  "httpStatus": 503,
  "errorCode": "GATEWAY_5XX",
  "durationMs": 3012,
  "nextRetryAt": "2026-09-09T08:16:01+08:00",
  "message": "gateway returned 503, scheduled retry"
}
```

**脱敏规则**：日志中禁止出现 `S3_ACCESS_KEY`、`S3_SECRET_KEY`、`GATEWAY_APP_ID`、`GATEWAY_APP_KEY`、完整客户 SFTP 密码/私钥、完整 S3 endpoint 凭据。凭据一律以 `${S3_SECRET_KEY}` 等占位符或 `***` 出现；`filePath` 可保留（运维必需），但不得携带宿主机绝对挂载路径以外的敏感信息。

### 事件命名规范

- 命名格式：`<子系统>_<动作>`，全大写下划线；状态迁移统一用 `STATE_TRANSITION`。
- 事件名稳定，**不随状态集合变化而新增状态名**。

| 事件名 | 级别 | 触发点 | 关键附加字段 |
|---|---|---|---|
| `SCAN_STARTED` | INFO | 某目录扫描周期开始 | `directoryId`, `customerSpace` |
| `SCAN_FINISHED` | INFO | 扫描周期结束 | `durationMs`, `discoveredCount` |
| `SCAN_FAILED` | ERROR | 扫描异常 | `errorType`, `errorMessage` |
| `FILE_DISCOVERED` | INFO | T1 创建 `file_version` | `fileVersionId`, `size`, `mtime` |
| `FILE_STABLE` | INFO | T4 进入 `READY` | `fingerprint`（可不落日志） |
| `FILE_DISAPPEARED` | WARN | 稳定前/读取前文件消失（T5/T7） | `cancelReason` |
| `TASK_CREATED` | INFO | `transfer_task` 落库 | `taskId` |
| `TASK_CLAIMED` | INFO | claim 成功，写 lease | `workerId`, `leaseUntil` |
| `STATE_TRANSITION` | INFO | 每次 `TaskStateMachine.transition` 成功 | `fromStatus`, `toStatus`, `reason` |
| `S3_UPLOAD_STARTED` | INFO | 开始 PUT | `size` |
| `S3_UPLOAD_SUCCESS` | INFO | T8 提交成功 | `durationMs`, `bytes` |
| `S3_UPLOAD_FAILURE` | ERROR | T9 迁移 | `errorType`, `errorCode` |
| `GATEWAY_REQUEST_STARTED` | INFO | 调用 Gateway 前 | `endpoint`, `gatewayRequestId` |
| `GATEWAY_SUCCESS` | INFO | T13 | `httpStatus`, `gatewayRequestId`, `durationMs` |
| `GATEWAY_FAILURE` | WARN/ERROR | T14/T15 | `httpStatus`, `errorCode`, `gatewayRequestId` |
| `TASK_RETRY_SCHEDULED` | WARN | 进入 `WAITING_RETRY` | `retryCount`, `nextRetryAt`, `errorCode` |
| `TASK_SUPERSEDED` | WARN | supersede 置 `CANCELLED` | `supersededByVersionNo`, `cancelReason` |
| `TASK_CANCELLED` | INFO | 文件消失/目录禁用 | `cancelReason` |
| `TASK_DELIVERED` | INFO | 终态 `DELIVERED` | `completedAt` |
| `RECOVERY_STALE_LEASE` | WARN | 启动/周期恢复 stale lease | `fromStatus`, `toStatus`, `workerId` |
| `LEASE_RENEWED` | DEBUG | 续租 | `leaseUntil` |
| `MANUAL_RETRY_REJECTED` | WARN | operator 对非 latest_stable 版本重试 | `reason=SUPERSEDED_BY_NEWER_VERSION` |

### 级别约定

| 级别 | 使用场景 | 是否告警来源 |
|---|---|---|
| `ERROR` | 需要人工介入或代表数据/状态异常：`SCAN_FAILED`、`S3_UPLOAD_FAILURE`、Gateway 5xx/认证失败、非法状态迁移、日志必需字段缺失 | 是 |
| `WARN` | 可自愈但需关注：`GATEWAY_FAILURE`（4xx/超时）、`TASK_RETRY_SCHEDULED`、`TASK_SUPERSEDED`、`RECOVERY_STALE_LEASE`、文件消失 | 是（聚合后） |
| `INFO` | 正常生命周期：扫描、发现、稳定、claim、上传/投递成功、状态迁移、DELIVERED/CANCELLED | 否（用于追溯） |
| `DEBUG` | 高频细节：续租、SQL 参数、重试退避计算 | 否（生产默认关闭，排障临时开启） |

- **禁止**用 `System.out`/`printStackTrace`；统一走 SLF4J。
- **禁止**把堆栈直接拼进 `message` 而不带结构化字段；`errorMessage` 可含简短原因，完整堆栈由采集器附加。
- 一条业务动作**恰好一条**主日志 + 可选一条迁移日志（`STATE_TRANSITION`）。

---

## 追踪（Trace / Correlation）

### `request_id` 生成与透传

- **入口生成**：每个扫描周期、每个 operator 请求、每个手工触发动作，在入口处生成一个 `requestId`（`req-` + 12 位随机/短 UUID），写入 MDC。
- **进程内透传**：`requestId` 随 MDC 在扫描线程 → 任务创建 → worker 执行全链路透传；跨线程池/虚拟线程时显式传递（不得依赖 `ThreadLocal` 隐式继承）。
- **跨 attempt 语义**：`requestId` 标识"一次逻辑动作/周期"，一个 task 的多次 attempt 可以共享同一 `requestId`（同一次 worker 执行内的上传+投递），也可以各自不同（不同调度轮次）。**attempt 级别的唯一关联靠 `taskId + attemptNo`，不靠 `requestId`**。
- **禁止**把 `requestId` 用作幂等键：At-least-once 语义下重复投递是允许的（基线 §0）。

### `gateway_request_id` 生成与透传

- **生成**：调用 B2B Gateway 前，由本系统生成 `gatewayRequestId`（`gw-` + 12 位），随请求头（如 `X-Request-Id`）透传；若 Gateway 返回自己的 request id，则额外记录为 `gatewayReturnedId`，两者并存。
- **透传**：无论成功、失败、超时、结果未知，**只要请求已发出**就必须记录 `gatewayRequestId`，用于与 Gateway 侧对账"是否真的投递成功"。
- **结果未知（T15）**：`outcome=UNKNOWN` + `errorCode=UNKNOWN_OUTCOME` + `gatewayRequestId` 必须同时记录，作为"可能已投递"的审计依据。
- **幂等**：`gatewayRequestId` 不作为去重键；Gateway 若支持幂等键，另用配置项透传，与本 id 解耦。

### 跨 attempt 关联

- 关联键：`taskId`（必需）→ `attemptNo` + `attempt_type`（`S3_UPLOAD` / `GATEWAY_DELIVER`）。
- 每次 attempt 在 `transfer_attempt` 落一行（`transfer_task_id`, `attempt_type`, `attempt_no`，基线 §4.2 UNIQUE 约束），日志同时写 `taskId`/`attemptNo`。
- 因此任意一条 attempt 日志都能通过 `taskId + attemptNo` 定位数据库记录，反之亦然。

### 从 NAS path 串到 attempt

链路（每一步都有结构化字段）：

```text
NAS 相对路径 filePath
   │  (nas_id, directory_id, relative_path) 唯一
   ▼
file.id = fileId
   │  file_id + fingerprint / version_no
   ▼
file_version.id = fileVersionId
   │  UNIQUE(file_version_id)
   ▼
transfer_task.id = taskId
   │  transfer_task_id + attempt_type + attempt_no
   ▼
transfer_attempt  ──►  结构化日志（requestId / gatewayRequestId / durationMs）
   │
   └──►  transfer_event（from_status → to_status，可选审计）
```

- 从 `filePath` 出发，用 `nas_id + directory_id + relative_path` 定位 `file`，再沿 `file_version → transfer_task → transfer_attempt` 下钻。
- 反向：从日志里的 `taskId` 可直接查 `transfer_task`，再 join 回 `file`/`directory`/`customer_space` 得到 `filePath`。
- `transfer_event`（整体设计 §52）提供状态迁移的时间线，用于回答"什么时候进入 `WAITING_RETRY`、是否被 supersede"。

---

## 健康检查

### liveness（存活）

| 检查项 | 探测方式 | 判定阈值 | 失败动作 |
|---|---|---|---|
| JVM 存活与主循环 | 进程响应 liveness 端点；检查关键调度线程未全部阻塞 | 连续 3 次探测失败（间隔 10s） | 由部署系统重启进程（lease 兜底恢复任务） |
| 启动自检完成 | 进程已越过启动阶段（见整体设计 §84 顺序） | 启动后 5 分钟内未就绪视为异常 | 重启并告警 |

### readiness（就绪）

| 检查项 | 探测方式 | 判定阈值 | 失败动作 |
|---|---|---|---|
| DB 可连接 | `SELECT 1`（短超时） | 连续 3 次失败 / 单次 > 5s | 标记 not-ready；**不接收新流量**（单机无 LB 时仅标记 + 告警） |
| 启动恢复完成 | Recovery 完成的内部标志（stale lease 处理完毕） | 启动后 10 分钟内未完成 | not-ready + 告警（RB-05） |
| Worker 线程池就绪 | 有界 executor 已创建且未拒绝 | 初始化失败 | not-ready |

### 依赖健康

| 依赖 | 检查项 | 探测方式 | 判定阈值 | 频率 |
|---|---|---|---|---|
| DB | 连接可用 + 连接池未耗尽 | `SELECT 1` + 活跃连接数 | 连续 3 次失败 / 单次 > 5s | 30s |
| NAS 目录可读性 | 每个 `enabled` 目录可读 + 可列目录 | `Files.isReadable` + 列一层目录 | 单目录连续 3 个扫描周期失败 | 随扫描周期 |
| S3 | bucket 可达 | `headBucket` 或 `listObjectsV2(maxKeys=1)` | 连续 3 次失败 / 单次 > 5s | 5m（避免高频探测） |
| Gateway | 轻量健康端点（如 `GET /health`） | HTTP HEAD/GET | 连续 5 次失败，或 p95 > `gateway.readTimeout` | 1m |

### 依赖不健康时是否摘流——明确结论

> **单机 V1 不自动摘流，只告警。**

理由与边界：

1. 单机部署没有负载均衡器，没有第二实例可以接管；"摘流"等价于"停止服务"，会把"等待依赖恢复"变成"系统不可用"，违反"不允许丢失文件"的可用性取向。
2. 依赖不健康时，正确行为是**软保护 + 告警**：
   - DB 不健康：停止 claim 新任务，在途任务靠 lease 恢复，恢复后继续（整体设计 §86）。
   - NAS 目录不可读：仅跳过该目录本周期扫描，其他目录照常，**不停止服务**。
   - S3 不健康：上传失败自然进入 `WAITING_RETRY` 无限重试（基线 §1 禁止 `FAILED`）。
   - Gateway 不健康：投递失败进入 `WAITING_RETRY` 无限重试。
3. `health_dependency_status` 仅用于告警（RB-09），**不参与 readiness 摘流判定**。
4. readiness 的 not-ready 只反映"本进程是否具备启动完成的内部状态"，不代表对外摘流；单机 V1 的对外可用性由"进程存活 + 无限重试"保证。

`【架构问题】O5`：若未来引入多实例/负载均衡，把 S3/Gateway 探测接入 readiness 会导致依赖抖动时全量摘流、放大故障；届时需改为"局部降级 + 熔断"，而非实例级摘流。

---

## 告警清单

> 级别约定：P1 = 立即人工介入（客户可能收不到文件）；P2 = 当班处理；P3 = 观察/优化项。
> 所有阈值中的 X/N 默认值见下表；实际部署按基线 §6/§7 配置项与业务量调整。

| 告警名 | 触发条件（含阈值与持续时间） | 级别 | 处置动作 | 对应 Runbook 小节 |
|---|---|---|---|---|
| `LongPendingTask`（长时间未完成任务） | `task_oldest_age_seconds{status="WAITING_RETRY"} > 6h` 持续 30m；> 24h 升级 P1 | P2 → P1 | 查最老任务与失败原因，判断是否依赖故障或需人工介入 | RB-01 |
| `CustomerGatewayFailureStreak`（某 customer 连续 N 次 Gateway 失败） | 同一 `customer_space` 最近 10 次 Gateway 调用全部失败（`gateway_failure_total` 连续），持续 15m | P1 | 确认是否客户侧 SFTP/Gateway 侧问题；必要时通知客户 | RB-02 |
| `DirectoryScanFailure`（目录连续 N 次扫描失败） | 同一 `directory_id` `scanner_scan_failure_total` 连续 ≥ 3 次（约 3 个扫描周期） | P2 | 检查 NAS 挂载/权限/目录是否存在；确认是否单目录还是全量 | RB-03 |
| `BacklogGrowing`（backlog 持续增长） | `task_backlog_total` 1h 增长 > 20%，且 `task_oldest_age_seconds{status="READY"} > 30m`，持续 1h | P2 | 检查 worker 并发/依赖健康；确认是否扫描风暴或并发不足 | RB-04 |
| `StaleLeaseHigh`（stale lease 数量超阈值） | `task_stale_lease > 20` 持续 10m；或 `> 0` 持续 30m | P2 | 检查进程是否崩溃/被强杀；确认 Recovery 是否在收敛 | RB-05 |
| `S3UploadFailureRate`（S3 上传失败率超阈值） | `rate(upload_failure_total[15m]) / rate(upload_total[15m]) > 0.2`，样本 ≥ 20，持续 15m | P1 | 检查 S3 凭据/网络/限流；确认是否系统性故障 | RB-06 |
| `NoDeliveredDeadSignal`（无 DELIVERED 产出/死寂） | 业务时段（默认工作日 08:00–20:00）`increase(task_delivered_total[2h]) == 0` 且 `task_backlog_total > 0` | P1 | 检查是否全链路阻塞（扫描/S3/Gateway）；确认是否业务低峰误报 | RB-07 |
| `GatewayHttp5xxSpike` | `rate(gateway_http_status_total{status_code=~"5.."}[10m]) > 0.1`（即 5xx 占比 > 10%）持续 10m | P1 | 同 RB-02 | RB-02 |
| `ScanStalled`（扫描停摆） | 某目录超过 2 个配置扫描周期无 `scanner_scan_total` 增长 | P2 | 检查调度线程与目录配置 | RB-03 |
| `SupersedeStorm`（supersede 风暴） | `rate(task_superseded_total[1h])` 超 7 日基线 5×，持续 1h | P3 | 检查是否上游频繁改文件/重复生成版本 | RB-08 |
| `DependencyUnhealthy` | `health_dependency_status{dependency=~"db|nas|s3|gateway"} == 0` 持续 15m | P2 | 按依赖逐项排查；**不摘流**，仅告警 | RB-09 |
| `UnknownOutcomeHigh` | `attempt_total{outcome="UNKNOWN"}` 占比 > 10%，持续 15m | P2 | 检查 Gateway 超时/连接中断；确认重复投递风险 | RB-02 |

### Runbook 索引（本文件内小节）

| Runbook | 名称 | 目标 | 首要查询/指标 |
|---|---|---|---|
| RB-01 | 长时间未完成任务 | 定位最老 `WAITING_RETRY` 任务并给出下一步 | 配方③④、`task_oldest_age_seconds`、`task_retry_total` |
| RB-02 | Gateway 失败/超时 | 区分客户侧、Gateway 侧、网络侧 | 配方⑤、`gateway_http_status_total`、`gateway_timeout_total` |
| RB-03 | 扫描失败/停摆 | 定位目录与 NAS 层原因 | `scanner_scan_failure_total`、`scanner_scan_total` |
| RB-04 | backlog 增长 | 判断是产能不足还是依赖阻塞 | 配方③、`task_ready`/`task_waiting_retry`/`task_uploading` |
| RB-05 | stale lease | 确认崩溃/强杀并验证恢复收敛 | 配方④、`recovery_stale_lease_recovered_total` |
| RB-06 | S3 上传失败 | 定位 S3 凭据/网络/限流 | `upload_failure_total`、`upload_duration_seconds` |
| RB-07 | 死寂（无 DELIVERED） | 快速判定全链路是否阻塞 | `task_delivered_total`、`scanner_scan_total`、配方③ |
| RB-08 | supersede 风暴 | 确认是否上游频繁改文件 | `task_superseded_total`、配方⑥ |
| RB-09 | 依赖不健康 | 按 DB/NAS/S3/Gateway 逐项排查 | `health_dependency_status` |

---

## 运维查询配方

> 下列 SQL 均为**示意，非 DDL 交付物**，用于精确表达查询语义；表名/字段名与基线 §4 一致。
> 部分配方依赖基线 `【架构问题】A2`（`file_version.version_no`）与 `A3`（`transfer_task.file_id`/`version_no`），若最终不引入冗余列，需改为 join `file_version`（见 `【架构问题】O3`）。
> `:file_path`、`:task_id`、`:customer_space` 为绑定参数（psql 中用 `\set` 或 `-v` 传入）。

### 配方①：这个文件为什么还没到客户？

```sql
-- 示意，非 DDL 交付物
SELECT
    cs.code              AS customer_space,
    f.relative_path      AS file_path,
    fv.version_no,
    fv.size,
    fv.mtime,
    fv.stable_at,
    t.id                 AS task_id,
    t.status             AS task_status,
    t.retry_count,
    t.next_retry_at,
    t.lease_until,
    t.worker_id,
    t.cancel_reason,
    t.s3_uploaded_at,
    a.attempt_no         AS last_attempt_no,
    a.attempt_type       AS last_attempt_type,
    a.outcome            AS last_outcome,
    a.error_code         AS last_error_code,
    a.http_status        AS last_http_status,
    a.gateway_request_id AS last_gateway_request_id,
    a.finished_at        AS last_attempt_finished_at
FROM file f
JOIN directory d        ON d.id = f.directory_id
JOIN customer_space cs  ON cs.id = d.customer_space_id
JOIN file_version fv    ON fv.file_id = f.id
JOIN transfer_task t    ON t.file_version_id = fv.id
LEFT JOIN LATERAL (
    SELECT * FROM transfer_attempt
    WHERE transfer_task_id = t.id
    ORDER BY attempt_no DESC
    LIMIT 1
) a ON true
WHERE f.relative_path = :file_path
ORDER BY fv.version_no DESC;
```

**期望输出列**：`customer_space`、`file_path`、`version_no`、`size`、`mtime`、`stable_at`、`task_id`、`task_status`、`retry_count`、`next_retry_at`、`lease_until`、`worker_id`、`cancel_reason`、`s3_uploaded_at`、`last_attempt_*`。

**解读方法**：
- 最新版本 `task_status='DELIVERED'` → 已投递，客户侧未收到则用 `last_gateway_request_id` 向 Gateway 对账（可能重复/顺序问题）。
- `WAITING_RETRY` → 看 `last_error_code`：S3 类走 RB-06，Gateway 类走 RB-02；看 `next_retry_at` 判断还要等多久（配方⑦）。
- `READY` 但长期不动 → 检查串行门（是否有更早版本非终态，配方⑥）与并发配额（配方③）。
- `CANCELLED` → 看 `cancel_reason`：`SUPERSEDED_BY_NEWER_VERSION` 说明已被更新版本取代；文件消失/目录禁用属预期。
- `UPLOADING`/`S3_UPLOADED`/`GATEWAY_DELIVERING` 且**有主**（`worker_id` 非空）且 `lease_until < now()` → stale lease（配方④）。

### 配方②：某个 task 的全部 attempt 历史

```sql
-- 示意，非 DDL 交付物
SELECT
    a.attempt_type,
    a.attempt_no,
    a.outcome,
    a.error_code,
    a.error_message,
    a.http_status,
    a.gateway_request_id,
    a.started_at,
    a.finished_at,
    a.duration_ms
FROM transfer_attempt a
WHERE a.transfer_task_id = :task_id
ORDER BY a.attempt_type, a.attempt_no;

-- 可选：状态迁移时间线（整体设计 §52）
SELECT e.created_at, e.from_status, e.to_status, e.reason, e.operator
FROM transfer_event e
WHERE e.task_id = :task_id
ORDER BY e.created_at;
```

**期望输出列**：`attempt_type`、`attempt_no`、`outcome`、`error_code`、`error_message`、`http_status`、`gateway_request_id`、`started_at`、`finished_at`、`duration_ms`。

**解读方法**：
- 按 `attempt_no` 递增看失败是否收敛；`outcome=UNKNOWN` 表示结果未知、允许重复投递（基线 T15）。
- 同一 `gateway_request_id` 出现在多条 → 重复投递的痕迹。
- `duration_ms` 接近超时阈值 → 超时风险，关注 `gateway_duration_seconds`。
- `transfer_event` 时间线用于确认迁移是否合法、supersede 何时发生。

### 配方③：当前 backlog 与最老任务年龄

```sql
-- 示意，非 DDL 交付物
SELECT
    status,
    count(*)                                   AS task_count,
    min(created_at)                            AS oldest_created_at,
    now() - min(created_at)                    AS oldest_age,
    min(next_retry_at) FILTER (WHERE next_retry_at IS NOT NULL) AS next_retry_at
FROM transfer_task
WHERE status IN ('READY', 'WAITING_RETRY', 'UPLOADING', 'S3_UPLOADED', 'GATEWAY_DELIVERING')
GROUP BY status
ORDER BY status;

-- backlog 总量（基线定义：READY + WAITING_RETRY）
SELECT count(*) AS backlog_total
FROM transfer_task
WHERE status IN ('READY', 'WAITING_RETRY');
```

**期望输出列**：`status`、`task_count`、`oldest_created_at`、`oldest_age`、`next_retry_at`；以及 `backlog_total`。

**解读方法**：
- `backlog_total` 增长且 `oldest_age` 增长 → backlog 告警（RB-04）：先看依赖健康（RB-09），再看并发是否打满（`task_uploading`/`task_delivering` 是否接近上限）。
- `READY` 有量但 `UPLOADING` 为 0 → claim 未发生：检查串行门与 worker 是否存活。
- `WAITING_RETRY` 占比高 → 多为依赖故障，看配方⑤。

### 配方④：哪些 lease 已过期

```sql
-- 示意，非 DDL 交付物
SELECT
    id                       AS task_id,
    status,
    worker_id,
    claimed_at,
    lease_until,
    now() - lease_until      AS overdue,
    file_id,
    version_no
FROM transfer_task
WHERE status IN ('UPLOADING', 'S3_UPLOADED', 'GATEWAY_DELIVERING')
  AND worker_id IS NOT NULL AND lease_until < now()   -- 仅「有主」且过期才算 stale
ORDER BY lease_until ASC;
```

**期望输出列**：`task_id`、`status`、`worker_id`、`claimed_at`、`lease_until`、`overdue`、`file_id`、`version_no`。

**解读方法**：
- 有结果 → 存在 stale lease（有主且过期），Recovery 应将其转 `READY`（T10）/`GATEWAY_DELIVERING`（T12）/`WAITING_RETRY`（T16）；若长时间不收敛 → RB-05。
- `worker_id` 非空且 lease 过期 → 原 worker 崩溃/被强杀；对照 `recovery_stale_lease_recovered_total` 确认恢复在跑。
- 数量突然增大 → 进程重启或长 GC/长时间执行。
- **无主任务不算 stale**：`GATEWAY_DELIVERING` 且 `worker_id IS NULL`（`lease_until` 为空）不会被本查询命中——它是 T12 恢复出的待认领待续投任务，属正常等待投递 claim 认领的状态，不是 stale lease。

### 配方⑤：某 customer 最近失败原因 Top N

```sql
-- 示意，非 DDL 交付物
SELECT
    a.error_code,
    count(*)              AS failures,
    max(a.finished_at)    AS last_seen,
    max(a.http_status)    AS last_http_status
FROM transfer_attempt a
JOIN transfer_task t    ON t.id = a.transfer_task_id
JOIN file_version fv    ON fv.id = t.file_version_id
JOIN file f             ON f.id = fv.file_id
JOIN directory d        ON d.id = f.directory_id
JOIN customer_space cs  ON cs.id = d.customer_space_id
WHERE cs.code = :customer_space
  AND a.attempt_type = 'GATEWAY_DELIVER'
  AND a.outcome IN ('FAILURE', 'UNKNOWN')
  AND a.finished_at >= now() - interval '24 hours'
GROUP BY a.error_code
ORDER BY failures DESC
LIMIT 10;
```

**期望输出列**：`error_code`、`failures`、`last_seen`、`last_http_status`。

**解读方法**：
- `error_code` 集中为 4xx/认证类 → 客户侧配置或凭据问题，走 RB-02 并联系客户。
- 集中为 5xx/超时 → Gateway 侧或网络问题，结合 `gateway_http_status_total`。
- `UNKNOWN` 占比高 → 结果未知、可能重复投递，需与 Gateway 对账。

### 配方⑥：某个 file 的所有版本与状态链

```sql
-- 示意，非 DDL 交付物
SELECT
    fv.version_no,
    fv.size,
    fv.mtime,
    fv.stable_at,
    fv.status            AS version_status,
    t.id                 AS task_id,
    t.status             AS task_status,
    t.retry_count,
    t.cancel_reason,
    t.superseded_by_version_no,
    t.completed_at,
    t.created_at,
    t.updated_at
FROM file f
JOIN file_version fv     ON fv.file_id = f.id
LEFT JOIN transfer_task t ON t.file_version_id = fv.id
WHERE f.relative_path = :file_path
ORDER BY fv.version_no ASC;

-- 状态迁移链（审计）
SELECT e.created_at, e.from_status, e.to_status, e.reason, e.operator
FROM transfer_event e
JOIN transfer_task t     ON t.id = e.task_id
JOIN file_version fv     ON fv.id = t.file_version_id
JOIN file f              ON f.id = fv.file_id
WHERE f.relative_path = :file_path
ORDER BY e.created_at ASC;
```

**期望输出列**：`version_no`、`size`、`mtime`、`stable_at`、`version_status`、`task_id`、`task_status`、`retry_count`、`cancel_reason`、`superseded_by_version_no`、`completed_at`、`created_at`、`updated_at`。

**解读方法**：
- 确认哪个是 `latest_stable`（`stable_at` 非空且非 `CANCELLED` 的最大 `version_no`）。
- 若存在更早版本非终态且阻塞了最新版本 → 等 supersede（基线 §3.3）；检查 `retry_count` 是否已越过 `supersede.afterFailedAttempts`。
- `cancel_reason=SUPERSEDED_BY_NEWER_VERSION` + `superseded_by_version_no` 即 supersede 证据。
- `version_status` 与 `task_status` 口径冲突时，**以 `transfer_task.status` 为权威**（见 `【架构问题】O7`/基线 A5）。

### 配方⑦：下次 retry 什么时候、还要等多久

```sql
-- 示意，非 DDL 交付物
SELECT
    id                          AS task_id,
    status,
    retry_count,
    next_retry_at,
    now()                       AS now_at,
    next_retry_at - now()       AS wait_remaining,
    (next_retry_at <= now())    AS due_now,
    worker_id,
    lease_until
FROM transfer_task
WHERE status = 'WAITING_RETRY'
  AND next_retry_at IS NOT NULL
ORDER BY next_retry_at ASC
LIMIT 50;
```

**期望输出列**：`task_id`、`status`、`retry_count`、`next_retry_at`、`now_at`、`wait_remaining`、`due_now`、`worker_id`、`lease_until`。

**解读方法**：
- `wait_remaining` 为负或 `due_now=true` 但任务仍停留 `WAITING_RETRY` → dispatcher 未 claim（检查 worker 存活/并发/串行门）。
- `wait_remaining` 随 `retry_count` 指数增长属正常退避；若 p95 超退避上限 2× → 退避配置异常。
- 同一 `file_id` 若被更早版本阻塞，即便 `due_now` 也不会执行（串行守卫，基线 §3.2），需等 supersede。

---

## Dashboard 建议

> 建议至少 4 个核心面板（吞吐、积压、失败率、延迟）+ 2 个辅助面板（Recovery/Lease、Scanner 健康）。所有面板均只使用本文件指标，标签低基数。

### 面板 1：吞吐（Throughput）

- **图表**：时间序列折线 + 堆叠面积；右上角放"今日 DELIVERED 总数"大数字。
- **关键指标**：`rate(scanner_files_discovered_total[5m])`、`rate(scanner_files_stable_total[5m])`、`rate(task_delivered_total[5m])`、`rate(task_cancelled_total[5m])`。
- **维度**：按 `customer_space` 堆叠；可切换 `directory_id` 下钻。
- **关注点**：discovered ≈ stable ≈ delivered 的漏斗是否等比；delivered 归零即死寂（RB-07）。

### 面板 2：积压（Backlog）

- **图表**：时间序列折线（多线）+ 状态热力图。
- **关键指标**：`task_ready`、`task_waiting_retry`、`task_backlog_total`、`task_uploading`、`task_delivering`、`task_oldest_age_seconds{status="READY"}`、`task_oldest_age_seconds{status="WAITING_RETRY"}`。
- **关注点**：backlog 上升但 uploading/delivering 不上升 → 产能或依赖阻塞（RB-04）；最老年龄持续上升 → 长时未完成（RB-01）。

### 面板 3：失败率（Failure Rate）

- **图表**：失败率折线（双线）+ Top N 错误码表格 + HTTP 状态堆叠柱。
- **关键指标**：`rate(upload_failure_total[5m]) / rate(upload_total[5m])`、`rate(gateway_failure_total[5m]) / rate(gateway_request_total[5m])`、`topk(10, gateway_http_status_total)`、`topk(10, sum by (error_code) (rate(task_retry_total[5m])))`。
- **关注点**：S3 失败率突增（RB-06）、某 customer Gateway 连续失败（RB-02）、5xx 占比（RB-02）。

### 面板 4：延迟（Latency）

- **图表**：p50/p95/p99 折线 + 直方图热力图。
- **关键指标**：`histogram_quantile(0.95, rate(upload_duration_seconds_bucket[5m]))`、`histogram_quantile(0.95, rate(gateway_duration_seconds_bucket[5m]))`、`histogram_quantile(0.95, rate(retry_wait_seconds_bucket[5m]))`、`histogram_quantile(0.95, rate(attempt_duration_seconds_bucket[5m]))`。
- **关注点**：Gateway p99 逼近 readTimeout → 超时与 UNKNOWN 风险；retry_wait p95 异常 → 退避配置问题。

### 面板 5（辅助）：Recovery / Lease

- **图表**：`task_stale_lease` 折线 + `rate(recovery_stale_lease_recovered_total[5m])` 柱。
- **关注点**：stale lease 突增 → 进程崩溃/强杀（RB-05）；recovered 持续 > 0 说明恢复在收敛。

### 面板 6（辅助）：Scanner 健康

- **图表**：每目录 `scanner_scan_total` 速率 + `scanner_scan_failure_total` 柱 + `scanner_scan_duration_seconds` p99。
- **关注点**：某目录速率归零（停摆，RB-03）、失败计数上升（RB-03）。

---

## 可观测性不变量

| # | 不变量 | 校验方式 |
|---|---|---|
| OBS1 | **每个状态迁移必须产生一条 `transfer_event` 与一条 `STATE_TRANSITION` 日志**（含 `fromStatus`/`toStatus`/`reason`） | 抽样比对 `transfer_event` 与日志；迁移数量与事件数量应一致 |
| OBS2 | **每个 attempt 必须可追溯到 `taskId`**：`transfer_attempt.transfer_task_id` 非空，日志必带 `taskId` + `attemptNo` | 数据库约束 + 日志字段校验 |
| OBS3 | **任何 `DELIVERED` 必须能回答"是谁在什么时候投递的"**：`transfer_task.completed_at` + 至少一条 `transfer_attempt(outcome='SUCCESS', attempt_type='GATEWAY_DELIVER')` 的 `gateway_request_id`/`finished_at` + 一条 `transfer_event(to_status='DELIVERED')` | 对每个 DELIVERED 任务执行配方② |
| OBS4 | 每条业务日志必带六个必需字段 `taskId`/`fileVersionId`/`fileId`/`directoryId`/`customerSpace`/`filePath` | 日志采集器校验规则，缺字段即 ERROR 计数 |
| OBS5 | 任何 `WAITING_RETRY` 必有非空 `next_retry_at`（T19 人工强制重试除外） | DB 断言查询 |
| OBS6 | 任何活跃执行状态（`UPLOADING`/`S3_UPLOADED`/`GATEWAY_DELIVERING`）必有 `lease_until` | DB 断言查询 |
| OBS7 | 任何 supersede 必产生 `task_cancelled_total{cancel_reason="SUPERSEDED_BY_NEWER_VERSION"}` 与 `transfer_event(operator='system')` | 指标 + 事件比对 |
| OBS8 | **无 `FAILED` 状态**：失败可见性通过 `WAITING_RETRY` + `transfer_attempt.outcome` 表达；`WAITING_RETRY` 任务永不消失 | 状态集合校验（基线 §1） |
| OBS9 | `requestId` 全链路可关联；`gatewayRequestId` 在请求已发出后必须记录（含超时/结果未知） | 日志抽样 |
| OBS10 | 指标口径以 PostgreSQL 为唯一事实源：所有 `task_*` gauge 来自 DB 采样，不用 JVM 内存计数推断 | 指标采集实现评审 |
| OBS11 | `task_delivered_total` 增量趋势与 DB `DELIVERED` 增量趋势一致（允许采集延迟） | 定时对账任务 |
| OBS12 | 指标标签集合固定为低基数字段；出现 `taskId`/`filePath` 等标签即视为缺陷 | 指标注册校验 |
| OBS13 | 一条业务动作恰好一条主日志；不得重复打印同一事件到多个 logger | 日志评审 |

---

## 核心问题：运维如何回答「这个文件为什么还没到客户？」

从 `filePath` 出发的完整排查路径（编号步骤，每步说明看什么表/指标/日志）：

1. **定位逻辑文件与归属**：用 `nas_id + directory_id + relative_path = :file_path` 查 `file`，join `directory` → `customer_space`。得到 `fileId`、`directoryId`、`customerSpace`。看：`file`、`directory`、`customer_space`。
2. **列出版本链、确认最新稳定版本**：查 `file_version` 按 `version_no`，找 `stable_at IS NOT NULL` 且非 `CANCELLED` 的最大版本（`latest_stable`）。看：`file_version.version_no`、`stable_at`、`status`。
3. **定位任务并读状态**：按 `file_version_id` 查 `transfer_task`，读 `status`、`retry_count`、`next_retry_at`、`lease_until`、`worker_id`、`cancel_reason`、`s3_uploaded_at`。看：`transfer_task`。
4. **看 attempt 历史定位失败点**：查 `transfer_attempt` 按 `attempt_no`，读 `attempt_type`、`outcome`、`error_code`、`http_status`、`gateway_request_id`、`duration_ms`。看：`transfer_attempt`。
5. **看状态迁移审计确认时间线**：查 `transfer_event`，确认何时进入 `WAITING_RETRY`、是否被 supersede、operator 是谁。看：`transfer_event`。
6. **按 `taskId` 检索结构化日志**：定位具体失败事件（`S3_UPLOAD_FAILURE`/`GATEWAY_FAILURE`/`GATEWAY_TIMEOUT`）与 `requestId`、`attemptNo`、`durationMs`。看：日志系统（event 字段）。
7. **看聚合指标判断是单文件还是系统性故障**：`upload_failure_total`、`gateway_failure_total`、`gateway_http_status_total`、`gateway_timeout_total`、`retry_wait_seconds`、`task_stale_lease`。看：Metrics 后端。
8. **看依赖健康**：`health_dependency_status{dependency=...}`；DB/NAS/S3/Gateway 是否有 0。看：健康检查端点 + 指标。
9. **检查串行门**：同 `file_id` 是否存在更早的 `version_no` 且状态 ∉ {`DELIVERED`,`CANCELLED`}（基线 §3.2）。若有 → 最新版本被阻塞，等 supersede。看：配方⑥。
10. **检查 supersede 是否该发生而未发生**：核对 `retry_count >= supersede.afterFailedAttempts` 或 `now() - last_attempt_finished_at >= supersede.afterWaiting`，且 lease 不活跃、宽限期已过（基线 §3.3）。看：`transfer_task` + `supersede.*` 配置。
11. **判断下次动作（S3 重传还是 Gateway 重投）**：`s3_uploaded_at IS NULL` → 下次走 `UPLOADING`（T18）；非空 → 下次走 `GATEWAY_DELIVERING`（T17）。看：`transfer_task.s3_uploaded_at`。
12. **给出结论与动作**：按失败类型进入对应 Runbook——Gateway 失败/超时 → RB-02；S3 失败 → RB-06；stale lease → RB-05；backlog/产能 → RB-04；扫描/依赖 → RB-03/RB-09；死寂 → RB-07。
13. **若任务已 `DELIVERED` 但客户称未收到**：用 `gateway_request_id` 与 Gateway 侧对账投递记录；确认是否为 At-least-once 下的重复/顺序问题或客户侧入库延迟。看：`transfer_attempt.gateway_request_id` + Gateway 侧日志。
14. **闭环**：把结论写回工单，记录 `taskId`、`fileVersionId`、`gateway_request_id`，便于后续审计（整体设计 §52）。

---

## 架构问题标注

`【架构问题】O1：指标标签与日志字段的边界`

- **问题**：基线 §57 要求日志携带 `filePath`，但若把 `filePath`/`taskId`/`fingerprint` 用作指标标签，会造成基数爆炸（整体设计 §83 反对过度索引，同理适用于指标）。
- **风险**：标签基数失控导致指标后端不可用、查询变慢，掩盖真实告警。
- **建议方案**：日志保留全部必需字段；指标只保留 `customer_space`、`directory_id`、`status`、`stage`、`outcome`、`error_code` 等低基数组件；高基数字段通过日志/DB 查询下钻。
- **对现有设计的影响**：Dashboard 无法按 `filePath` 聚合，需要依赖"运维查询配方"与日志检索，符合整体设计 §60 的查询模型。

`【架构问题】O2：§58 指标命名与状态集合的映射`

- **问题**：整体设计 §58 列出 `gateway_retry_total` 等指标，但基线 §1 无独立 `RETRY` 状态，retry 是 `transfer_task` 属性。
- **风险**：指标口径与状态机不一致，可能诱导新增状态。
- **建议方案**：retry 指标统一按 `stage`（upload/gateway）+ `error_code` 标注，不新增状态；本文件 `task_retry_total`/`gateway_retry_total` 均遵守此约定。
- **对现有设计的影响**：指标命名需在实现时对齐，不改变状态集合。

`【架构问题】O3：运维查询配方依赖 A2/A3 未决列`

- **问题**：配方①③④⑥ 使用 `transfer_task.file_id`/`version_no`（基线 A3）与 `file_version.version_no`（基线 A2），这两列在整体设计 §33 DDL 中缺失。
- **风险**：配方不可直接执行；串行门与 supersede 查询需额外 join，性能与可读性下降。
- **建议方案**：确认并补齐 `transfer_task.file_id`、`transfer_task.version_no`、`file_version.version_no`（含 `(file_id, version_no)` 索引，基线 §4.3）；若不补，则配方改为 join `file_version`。
- **对现有设计的影响**：仅影响查询与索引，不改变状态机与并发模型。

**【架构问题】O4：transfer_attempt.outcome 未决（基线 A4）**

- **问题**：`success BOOLEAN` 无法表达"结果未知"（T15），而失败率、`outcome=UNKNOWN` 告警、失败原因 Top N 都依赖三态。
- **风险**：超时/连接中断被记成普通失败，掩盖重复投递风险；告警阈值失真。
- **建议方案**：引入 `outcome` 枚举 `SUCCESS`/`FAILURE`/`UNKNOWN`（与基线 A4 建议一致）。
- **对现有设计的影响**：`transfer_attempt` 表需增列；指标与配方①⑤⑦ 的输出列依赖它。

`【架构问题】O5：依赖健康是否参与 readiness 摘流`

- **问题**：把 S3/Gateway 探测接入 readiness，在单机 V1 会导致依赖抖动时服务被判不可用。
- **风险**：把"等待依赖恢复"变成"停机"，放大故障；多实例下会全量摘流。
- **建议方案**：V1 依赖探测只用于告警（`health_dependency_status`，RB-09），不摘流；readiness 只反映内部启动完成状态。
- **对现有设计的影响**：无架构变更；健康检查实现需区分 liveness/readiness/依赖告警三类。

**【架构问题】O6：filePath 语义依赖 A6**

- **问题**：基线 A6 未定义 `directory.path` 与 `relative_path` 的拼接规则，导致日志中的 `filePath` 是"相对目录路径"还是"NAS 绝对路径"存在歧义。
- **风险**：运维按 `filePath` 检索日志/查询时可能对不上；S3 Object Key 拼接（基线 §5）也可能出错。
- **建议方案**：明确 `filePath = file.relative_path`（相对目录），另设 `nasMountPath`/`directoryPath` 字段用于展示绝对路径；日志字段定义固化。
- **对现有设计的影响**：仅字段语义澄清，不改变数据模型结构。

**【架构问题】O7：file_version.status 与 transfer_task.status 双状态口径（基线 A5）**

- **问题**：两处状态并存，可观测性指标/查询可能口径不一致。
- **风险**：同一文件在指标与查询里呈现不同状态，误导运维。
- **建议方案**：**以 `transfer_task.status` 为权威**；`file_version.status` 仅作辅助（如"是否稳定"），指标与告警统一基于 `transfer_task`。
- **对现有设计的影响**：需在实现与文档中明确口径，避免双写漂移。

`【架构问题】O8：死寂告警的业务时段基线`

- **问题**：`NoDeliveredDeadSignal` 在无文件日/业务低峰会误报。
- **风险**：告警疲劳，真实死寂被忽略。
- **建议方案**：引入业务时段配置（默认工作日 08:00–20:00）与 7 日同期基线，或改为"backlog > 0 且 2h 无 DELIVERED"复合条件。
- **对现有设计的影响**：需在配置模型（`07-configuration-model.md`）中增加观测时段配置项。
