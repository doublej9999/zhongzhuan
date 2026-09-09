# 02 Non-Functional Requirements（非功能需求）

> 本文是 Phase 0 交付文档之一，**唯一事实源**为 `phase0-baseline.md`（下称"基线"）。
> 任何陈述与基线冲突时以基线为准；与已确认架构冲突处一律用 `【架构问题】` 标注，不擅自修改架构。
> 术语与基线 §9 一致。本文只描述**可量化、可验证**的非功能目标，不写业务代码、不写 DDL 交付物。

---

## 本部分范围

本文定义 V1 非功能需求（NFR），覆盖 8 个维度，顺序固定：

1. **Reliability（可靠性）**——不丢文件、At-least-once 的边界、稳定判定正确性、串行不变量。
2. **Availability（可用性）**——单机部署下的可用性目标、依赖降级行为、维护窗口。
3. **Performance（性能）**——吞吐、延迟、扫描性能、DB 查询性能、资源占用。
4. **Concurrency（并发）**——全局/目录级并发上限、有界队列与背压、资源占用上界。
5. **Observability（可观测性）**——日志字段、Metrics 覆盖率、告警时延、审计可追溯性。
6. **Security（安全）**——凭据管理、传输加密、NAS 只读、最小权限。
7. **Data Retention（数据保留）**——1 年保留、仅清理终态、清理顺序与数据量上界。
8. **Recoverability（可恢复性）**——RPO/RTO 定义与论证、崩溃恢复正确性、备份。

每一条 NFR 均使用统一列：`编号 | 维度 | 指标 | 目标值 | 验证方式 | 不达标判定`。
目标值一律为数字或区间；验证方式一律为可执行动作（Testcontainers 集成测试、故障注入脚本、Prometheus 查询、日志断言、`kill -9` 场景、DB 断言）。

---

## 本部分不做什么

- **不定义功能行为**：状态集合、迁移矩阵、supersede 算法由基线 §1–§3 定义，本文只引用，不重述、不改写。
- **不定义接口契约**：Gateway API、S3 Object Key 规则见基线 §5 与整体设计 §22/§40。
- **不定义数据模型 DDL**：表结构与索引见基线 §4 与整体设计 §31–§35/§82。本文只给容量与索引量级估算。
- **不承诺超出业务需求的可靠性等级**：不追求 exactly-once、跨系统 ACID、99.99% 可用性、亚秒级端到端延迟（见"明确不追求的目标"）。
- **不引入新技术组件**：不引入 Kafka/RabbitMQ/Redis/Kubernetes/分布式事务/分布式锁/S3 对象版本控制（基线 §0）。
- **不包含实现代码**：不创建 `*.java`、`*.sql`、`pom.xml`、`mvnw`。
- **不做压测/故障注入的实际执行**：本文只定义"验证方式"，实际执行属于 Phase 1+ 的测试阶段。

---

## 对应整体设计文档章节

| 本文内容 | 整体设计文档章节 |
|---|---|
| 规模基线与容量估算 | §2.1 NAS、§81 容量估算、§83 建议不要过度索引 |
| 并发上限目标 | §42 一个文件默认一个 Worker、§43 并发控制、§44 为什么要限制并发、§45 Scheduler 与 Worker 解耦、§46 Dispatcher、§47 背压、§71 Virtual Threads 是否使用、§72 推荐的并发结构 |
| 延迟与超时预算 | §9 文件稳定性检测、§10 Scanner 设计、§11 扫描过程、§16 Lease、§24 Gateway 超时、§25 Retry、§26 Exponential Backoff |
| 可恢复性目标 | §17 Crash Recovery、§64 JVM Crash 流程、§65 Scanner Crash、§84 系统启动恢复、§85 Graceful Shutdown、§86 故障场景矩阵 |
| 数据保留 | §54 数据保留、§55 不建议 PostgreSQL 保存应用日志 |
| 可观测性 | §57 日志设计、§58 Metrics、§59 最重要的告警、§60 运维查询模型、§52 Audit |
| 安全 | §56 敏感信息、§73 SMB 注意事项 |
| 可靠性 | §18 为什么允许重复执行、§19 同一路径版本串行、§21 版本最终一致性、§68 Exactly-once 的处理、§69 关键不变量、§87 最终一致性定义、§91 一个最终需要特别注意的实现细节 |
| 验收方式 | §92 V1 验收标准 |

---

## 规模基线（所有目标值的输入）

| 参数 | 值 | 来源 |
|---|---|---|
| NAS 数量 | 2 | 基线 §0 |
| 目录数量 | 约 20 | 基线 §0 |
| 文件数 | 1000 文件/天 | 基线 §0 |
| 数据量 | 5 GB/天（本文按 5 GiB 计算，1 GiB = 1024 MiB） | 基线 §0 |
| 单文件大小 | 1 KB ~ 10 MB | 基线 §0 |
| 单文件均值 | 5 GiB ÷ 1000 = 5.12 MiB | 推导 |
| 单目录文件数上界 | 10,000 | 基线 §0 |
| 逻辑文件数上界 | 20 × 10,000 = 200,000 | 推导 |
| 部署形态 | 单机（单 JVM + 单 PostgreSQL） | 基线 §0 |
| 一致性语义 | At-least-once，允许重复，不允许丢失 | 基线 §0 |
| 事实来源 | PostgreSQL 为唯一 Source of Truth | 基线 §0 |
| 数据保留 | 1 年（365 天），仅清理终态 | 基线 §8 |

**权威阈值（不得与之矛盾）**：

| 配置项 | 值 |
|---|---|
| `worker.upload.maxConcurrency` | 8 |
| `worker.gateway.maxConcurrency` | 8 |
| 目录级并发 | 2 ~ 4 |
| `lease.duration` | 5m |
| `retry.initialDelay` / `multiplier` / `maxDelay` / `jitter` | 30s / 2 / 1h / ±20% |
| 数据保留 | 1 年 |
| `supersede.gracePeriod` / `afterFailedAttempts` / `afterWaiting` | 15m / 5 / 30m |

---

## NFR 总表

### Reliability（可靠性）

| 编号 | 维度 | 指标 | 目标值 | 验证方式 | 不达标判定 |
|---|---|---|---|---|---|
| NFR-01 | Reliability | 已稳定文件版本的投递完成率（依赖正常前提下） | 连续 7 天，`DELIVERED` 任务数 ÷ 已进入 `READY` 任务数 ≥ 99.9%，且"永久停滞"任务数 = 0 | Testcontainers 集成测试 + 7×24h 长稳跑；DB 断言 `SELECT count(*) FROM transfer_task WHERE status NOT IN ('DELIVERED','CANCELLED') AND created_at < now() - interval '2 hours'` | 任一任务停滞 > 2h 且无 `next_retry_at` 推进；或完成率 < 99.9% |
| NFR-02 | Reliability | 崩溃不丢文件（At-least-once 的"不丢"侧） | 在 S3 PUT 中 / DB 提交前后 / Gateway 调用中注入 `kill -9` 共 100 次，最终 `DELIVERED` 数 = 注入前的任务总数，丢失数 = 0 | 故障注入脚本（`kill -9` + 重启）+ DB 断言每个 `file_version` 存在且状态 ∈ {非终态, `DELIVERED`, `CANCELLED`} | 任一 `file_version` 无对应 `transfer_task`，或任一任务被物理删除 |
| NFR-03 | Reliability | 重复投递上界（允许重复，但必须可审计、有上界） | 单次 `kill -9` 注入下，同一 `file_version` 的 Gateway **成功**投递次数 ≤ 3 次（P99），且每次均有 `transfer_attempt` 记录（记录缺失数 = 0） | 故障注入脚本 + `transfer_attempt` 计数断言；Prometheus 查询 `sum(gateway_success_total) by (file_version_id)` | 成功投递 > 3 次，或存在无 attempt 记录的成功投递 |
| NFR-04 | Reliability | 稳定判定正确性 | 文件持续写入（size 每 1s 变化）的 5 分钟内进入 `READY` 次数 = 0；写入完成后 ≤ 2 个扫描周期内进入 `READY` | 集成测试：后台线程持续写文件 5 分钟 + 扫描；DB 状态断言 | 写入过程中出现 1 次 `READY` 或 `UPLOADING` |
| NFR-05 | Reliability | 同路径串行不变量 I2 | 任意采样时刻，同一 `file_id` 处于执行阶段（`UPLOADING`/`S3_UPLOADED`/`GATEWAY_DELIVERING`）的非终态任务数 ≤ 1 | 并发注入 v1/v2 各 100 次 + 每 100ms 采样 DB 断言；SQL 见基线 §3.2 串行守卫 | 任意一次采样结果 ≥ 2 |
| NFR-06 | Reliability | Scanner 幂等性（不变量 I6） | 同一目录连续扫描 100 次，`file_version` 与 `transfer_task` 新增行数 = 0（无变化前提下） | 集成测试：连续触发 100 次扫描 + 行数前后比对 | 新增行数 > 0 |

### Availability（可用性）

| 编号 | 维度 | 指标 | 目标值 | 验证方式 | 不达标判定 |
|---|---|---|---|---|---|
| NFR-07 | Availability | 单机月度可用性 | ≥ 99.5%（月度不可用时间 ≤ 3.6 小时）；**明确不追求 99.99%** | Prometheus `up{job="nas-s3-gateway-transfer"}` 查询，按 30 天窗口计算 | 月度可用性 < 99.5% |
| NFR-08 | Availability | 计划内重启窗口 | 单次 `SIGTERM` 优雅关闭 + 重启恢复 ≤ 5 分钟；在途任务完成率 ≥ 90% | 集成测试：上传中发送 `SIGTERM`，测量到"服务恢复可 claim"的时长 | 重启窗口 > 5 分钟，或在途任务完成率 < 90% |
| NFR-09 | Availability | Gateway 不可用时的降级可用性 | 扫描与 S3 上传继续工作（`scan_success_total` 不中断）；Gateway 恢复后 ≤ 1 个调度周期 + 1 次最大退避（≤ 1h5m）内自动恢复投递；任务丢失数 = 0 | 故障注入：用 mock Gateway 返回 503 持续 30 分钟再恢复；观测指标与最终状态 | 扫描中断，或恢复后 > 1h5m 仍无 `DELIVERED` 增长，或任务丢失 |
| NFR-10 | Availability | PostgreSQL 不可用时的安全降级 | 0 次"无 DB 保护的状态变更"；DB 恢复后 ≤ 5 分钟内自动继续 claim | 故障注入：`docker pause` PostgreSQL 60 秒后 `unpause`；日志断言无状态迁移成功日志 | 出现任一未落库的状态推进，或恢复后 > 5 分钟未继续 |
| NFR-11 | Availability | 依赖依赖度 | 可用性依赖清单：NAS 挂载（SMB/CIFS）、S3、B2B Gateway、PostgreSQL；任一外部依赖故障时系统进程存活率 = 100% | 逐个依赖故障注入 + 进程存活断言（`/actuator/health` 或 PID 存活） | 任一依赖故障导致 JVM 退出或不可恢复 |

### Performance（性能）

| 编号 | 维度 | 指标 | 目标值 | 验证方式 | 不达标判定 |
|---|---|---|---|---|---|
| NFR-12 | Performance | 平均吞吐 | ≥ 5 GiB/天（= 60.7 KiB/s、1 文件/86.4s）；单机容量 ≥ 15 GiB/天（= 177 KiB/s，3× 均值） | 压测脚本灌入 15 GiB / 3000 文件，24h 内全部 `DELIVERED` | 24h 内未清空，或 `upload_bytes_total` 增速 < 177 KiB/s |
| NFR-13 | Performance | 峰值吞吐 | ≥ 1.5 MiB/s（2h 窗口峰值 0.57 MiB/s 的 2.6×；1h 极端窗口 1.42 MiB/s 的 1.05×） | 压测：2 小时内注入 4 GiB / 800 文件，观测 `upload_duration_seconds` 与吞吐曲线 | 峰值吞吐 < 1.5 MiB/s，或 2h 窗口内积压未在窗口结束后 30 分钟内清空 |
| NFR-14 | Performance | 单文件上传时长（P95） | 1 KB ≤ 2s；1 MiB ≤ 10s；10 MB ≤ 60s（按单流 ≥ 0.5 MiB/s） | Prometheus 查询 `histogram_quantile(0.95, rate(upload_duration_seconds_bucket[5m]))` + 压测 | 任一档 P95 超限 |
| NFR-15 | Performance | 端到端投递延迟（`stable_at` → `completed_at`） | P50 ≤ 5 分钟；P95 ≤ 10 分钟；P99 ≤ 30 分钟（依赖正常时） | 集成测试埋点 + Prometheus 分位查询 | P95 > 10 分钟或 P99 > 30 分钟 |
| NFR-16 | Performance | 扫描性能 | 单目录 10,000 文件全量扫描 ≤ 120 秒；对 NAS 的 stat/list 速率 ≤ 2,000 ops/s；扫描过程不把整个目录加载进内存（堆增长 ≤ 64 MiB） | 集成测试：造 10,000 文件目录 + 计时 + JFR/堆采样 | 单目录扫描 > 120s，或 stat 速率 > 2,000 ops/s，或堆增长 > 64 MiB |
| NFR-17 | Performance | DB 查询性能（36.5 万任务规模下） | claim 查询 P95 ≤ 50ms；stale lease 恢复查询 P95 ≤ 100ms；单次状态迁移 ≤ 20ms；清理批处理单批 ≤ 2s | Testcontainers 灌入 365,000 条 task 后执行 `EXPLAIN ANALYZE` + 计时 | 任一查询 P95 超限 |
| NFR-18 | Performance | 资源占用 | JVM 堆 ≤ 2 GiB；RSS ≤ 4 GiB；平均 CPU ≤ 2 核、峰值 ≤ 4 核；单机 4C8G 即可运行 | 长稳跑 24h + Prometheus `process_resident_memory_bytes` / `process_cpu_usage` | 堆 > 2 GiB 或 RSS > 4 GiB 或峰值 CPU > 4 核 |

### Concurrency（并发）

| 编号 | 维度 | 指标 | 目标值 | 验证方式 | 不达标判定 |
|---|---|---|---|---|---|
| NFR-19 | Concurrency | 全局并发上限 | upload 活跃 worker ≤ 8（`worker.upload.maxConcurrency=8`）；gateway 活跃 worker ≤ 8（`worker.gateway.maxConcurrency=8`） | 集成测试：注入 1000 任务，每 100ms 采样 `task_uploading` / `task_delivering` | 采样值 > 8 |
| NFR-20 | Concurrency | 目录级并发上限 | 单目录活跃 worker ≤ `directory.max_concurrency`，取值 2 ~ 4 | 集成测试：单目录注入 200 任务，采样按 directory 分组的活跃数 | 任一目录活跃数 > 4 |
| NFR-21 | Concurrency | 有效并发公式 | 有效并发 = `min(阶段全局并发, 目录并发)`，且再叠加 Semaphore 兜底；任意时刻总活跃 ≤ `min(8, 4) × 目录数` 且 ≤ 8 | 代码审查 + 并发测试断言 Semaphore 计数 ≤ 8 | 出现绕过 Semaphore 的执行路径 |
| NFR-22 | Concurrency | 有界队列与背压 | executor 队列容量 = `worker.queue-capacity`（默认 **16** = 2 × 单阶段 max-concurrency）；JVM 内待执行任务数 ≤ 执行中(≤ 8) + 队列(≤ 16) = **24**；队列满时停止 claim（不把 DB backlog 拉进内存） | 集成测试：灌入 10,000 任务，断言 JVM 内任务对象 ≤ 24、DB 中 `READY` 不被清空 | JVM 内任务数 > 24，或队列无界 |
| NFR-23 | Concurrency | 禁止无界并发 | `Executors.newCachedThreadPool()` / 无界队列 出现次数 = 0；JVM 活跃线程数 ≤ 64 | 静态检查（grep/ArchUnit）+ 运行期 `jvm_threads_live_threads` 断言 | 出现 1 处无界线程池/队列，或活跃线程 > 64 |
| NFR-24 | Concurrency | 同版本不被并发执行 | 同一 `transfer_task` 同时被 2 个 worker 执行的次数 = 0（`FOR UPDATE SKIP LOCKED` + 条件更新 + lease） | 并发测试：8 worker 抢 1000 任务，断言 `claimed_at` 唯一、attempt 无并发重叠 | 出现任一任务被并发执行 |
| NFR-25 | Concurrency | Virtual Thread 使用约束 | 若采用虚拟线程，必须配 Semaphore；虚拟线程数 ≤ 24（= 活跃 8 + 队列 16），载体线程数 ≤ CPU 核数（4） | 代码审查 + 运行期线程 dump 断言 | 虚拟线程数 > 24 或缺少 Semaphore |

### Observability（可观测性）

| 编号 | 维度 | 指标 | 目标值 | 验证方式 | 不达标判定 |
|---|---|---|---|---|---|
| NFR-26 | Observability | 日志必带字段覆盖率 | 关键事件日志中 `taskId`、`fileVersionId`、`fileId`、`directoryId`、`customerSpace`、`filePath` 缺失率 = 0% | 日志断言测试：正则匹配每条关键日志（`event=S3_UPLOAD_SUCCESS` 等）的 6 个字段 | 任一关键日志缺任一字段 |
| NFR-27 | Observability | Metrics 覆盖率 | 整体设计 §58 列出的 ≥ 24 个指标全部暴露于 `/actuator/prometheus`，缺失数 = 0；采集间隔 15s | Prometheus 查询 `count({__name__=~"scan_.*|upload_.*|gateway_.*|task_.*"})` ≥ 24 | 缺失 > 0 |
| NFR-28 | Observability | 告警时延与覆盖 | 覆盖整体设计 §59 的 6 类告警，规则数 ≥ 6；触发条件满足后 ≤ 2 个评估周期（≤ 2 分钟）内产生告警 | 故障注入 + Prometheus `ALERTS` 查询，测量从注入到 `firing` 的时长 | 告警规则 < 6 条，或时延 > 2 分钟 |
| NFR-29 | Observability | 全链路可追溯 | 任一 `taskId` 可在 ≤ 3 分钟内定位全链路（日志 + `transfer_attempt` + `transfer_event`）；状态迁移审计覆盖率 = 100%，审计写入延迟 ≤ 5s | 运维演练脚本：随机抽 20 个 `taskId` 走查 + DB 断言 | 任一 `taskId` 无法定位，或迁移无审计记录 |
| NFR-30 | Observability | 敏感信息零泄露 | 日志、Metrics、DB 中出现密钥明文（`S3_SECRET_KEY`、`GATEWAY_APP_KEY`）的命中数 = 0 | 自动化扫描：对日志文件与 DB 全表 grep 凭据占位符的实际值 | 命中 > 0 |

### Security（安全）

| 编号 | 维度 | 指标 | 目标值 | 验证方式 | 不达标判定 |
|---|---|---|---|---|---|
| NFR-31 | Security | 凭据不落盘/不落库 | 密钥出现在 `application.yml`、DB 表、日志中的次数 = 0；一律使用 `${S3_ACCESS_KEY}`、`${S3_SECRET_KEY}`、`${GATEWAY_APP_ID}`、`${GATEWAY_APP_KEY}` 环境变量占位符 | 静态扫描（grep 明文） + DB 全表扫描 + 日志断言 | 命中 > 0 |
| NFR-32 | Security | 传输加密 | 100% 出站 S3/Gateway 请求使用 TLS 1.2+；非 TLS 连接数 = 0（若 Gateway 为内网 HTTP，必须走网络白名单并单独登记） | 集成测试抓包 + `openssl s_client` 协议断言 + 配置审查 | 出现 1 个 TLS < 1.2 的连接 |
| NFR-33 | Security | NAS 只读 | 对 NAS 的写/删除/移动调用次数 = 0；挂载优先使用 `ro` 选项或代码层只读 API | 代码审查（禁用写 API） + 故障注入：尝试写调用应抛异常 | 出现 1 次 NAS 写操作 |
| NFR-34 | Security | 最小权限 | S3 凭据仅授予指定 bucket/prefix 的 `PutObject`（+ 必要的 `GetObject`）；DB 用户仅授予业务表 DML + 必要 DDL；越权操作成功次数 = 0 | 权限矩阵测试：用受限凭据执行未授权操作，期望 403 | 出现 1 次越权成功 |
| NFR-35 | Security | 认证/授权失败处理 | Gateway 401/403 时，首次失败即产生告警（≤ 1 次尝试）；后续重试遵守 30s→1h 退避，不得快速循环（1 分钟内重试次数 ≤ 2） | 故障注入：mock 返回 401；统计 1 分钟内重试次数 + 告警查询 | 1 分钟内重试 > 2 次，或未告警 |

### Data Retention（数据保留）

| 编号 | 维度 | 指标 | 目标值 | 验证方式 | 不达标判定 |
|---|---|---|---|---|---|
| NFR-36 | Data Retention | 保留期 | 业务数据保留 1 年（365 天）；仅清理 `DELIVERED`/`CANCELLED` 且 `created_at < now() - 1 year` | maintenance job 集成测试：造 400 天前与 300 天前的终态/非终态记录，断言清理结果 | 保留期 ≠ 365 天，或清理了非终态记录 |
| NFR-37 | Data Retention | 非终态永不清理（不变量 I7） | 非终态任务（`DISCOVERED`/`STABILITY_CHECK`/`READY`/`UPLOADING`/`S3_UPLOADED`/`GATEWAY_DELIVERING`/`WAITING_RETRY`）被清理的行数 = 0 | 集成测试：造 5 年前的非终态任务，跑清理 job，断言行数不变 | 非终态被删除 > 0 行 |
| NFR-38 | Data Retention | 清理任务性能与锁影响 | 每日（或每小时）执行 1 次；单次清理 ≤ 5 分钟；批量 ≤ 5,000 行/批；单批锁等待 ≤ 1s | 集成测试：灌入 365,000 行后跑清理，测量时长与 `pg_stat_activity` 锁等待 | 单次 > 5 分钟或锁等待 > 1s |
| NFR-39 | Data Retention | 清理顺序与 FK 完整性 | 严格按 `transfer_event` → `transfer_attempt` → `transfer_task` → `file_version` → `file` 逐级删除；孤儿行 = 0 | 集成测试 + 外键约束断言查询 | 出现 1 条孤儿行或 FK 违规 |
| NFR-40 | Data Retention | 1 年数据量上界 | `file_version` = 365,000 行；`transfer_task` = 365,000 行；`transfer_attempt` ≈ 803,000 行；`transfer_event` ≈ 2,555,000 行；DB 总占用 ≤ 10 GiB（实测估算 ≈ 2.5 GiB） | 容量脚本按真实行宽测算 + `pg_total_relation_size` | 任一行数或总占用超上界 |
| NFR-41 | Data Retention | 应用日志不入 PostgreSQL | PostgreSQL 中非业务日志表/行数 = 0；日志走 stdout → 采集（Loki/ELK/Splunk） | DB schema 审查 + 表清单断言 | 出现日志表或日志行 |

### Recoverability（可恢复性）

| 编号 | 维度 | 指标 | 目标值 | 验证方式 | 不达标判定 |
|---|---|---|---|---|---|
| NFR-42 | Recoverability | RTO（单 JVM 崩溃） | ≤ 8 分钟 = `lease.duration`(5m) + 调度周期(≤ 1m) + 启动与恢复扫描(≤ 2m)；见"可恢复性目标"论证 | `kill -9` 故障注入 + 计时脚本：从崩溃到该任务重新进入 `READY`/`GATEWAY_DELIVERING` | 任一任务恢复时间 > 8 分钟 |
| NFR-43 | Recoverability | RTO（整机故障 / 需重建） | ≤ 30 分钟（人工拉起应用 + 恢复 DB 连接） | 演练：停整机后按运维手册恢复并计时 | > 30 分钟 |
| NFR-44 | Recoverability | RPO（任务层） | 0：已落库的 `file_version` 与 `transfer_task` 不因崩溃丢失 | `kill -9` 100 次 + DB 行数比对 | 任一已落库任务丢失 |
| NFR-45 | Recoverability | RPO（数据库层） | ≤ 15 分钟（依赖 WAL 归档/PITR）；若仅有每日全量备份则 RPO ≤ 24 小时 | 备份恢复演练：恢复到指定时间点并校验行数 | RPO > 15 分钟（未启用 WAL 归档） |
| NFR-46 | Recoverability | 恢复动作并发安全 | 恢复动作 100% 使用条件更新（`WHERE id=? AND status=? AND lease_until < now()`）；恢复覆盖活跃 worker 的次数 = 0 | 并发测试：恢复线程与 worker 同时运行，断言无覆盖 | 出现 1 次覆盖 |
| NFR-47 | Recoverability | 恢复后重复投递次数 | 每次崩溃恢复导致的重投次数 ≤ 1 次/task | 故障注入 + `transfer_attempt` 计数 | > 1 次 |
| NFR-48 | Recoverability | 备份与演练 | 每日全量 + 连续 WAL 归档；每季度 1 次恢复演练；演练恢复时长 ≤ 60 分钟、数据一致性校验通过率 = 100% | 备份/恢复演练记录 + 校验脚本 | 季度内未演练，或恢复 > 60 分钟，或校验失败 |
| NFR-49 | Recoverability | 优雅关闭正确性 | `SIGTERM` 后：停止新扫描 → 停止 claim → 在途 worker 有超时等待 → 尽量完成上传 → 释放 lease → 退出；超时强杀后由 lease 兜底，任务丢失数 = 0 | 集成测试：上传中发 `SIGTERM`，断言关闭顺序与最终状态 | 顺序错误或任务丢失 |
| NFR-50 | Recoverability | 启动顺序强制 | DB 连接 → Recovery（stale lease）→ Retry/Dispatcher + Worker → Scanner（最后启动）；顺序违规次数 = 0 | 启动日志断言 + 代码审查 | 顺序违规 |

---

## 容量与吞吐估算

### 输入参数

- 文件数：1,000 文件/天
- 数据量：5 GB/天（按 5 GiB = 5 × 1024 = 5,120 MiB 计算）
- 单文件均值：5,120 MiB ÷ 1,000 = **5.12 MiB**
- 单文件范围：1 KB ~ 10 MB
- 目录数：20；单目录文件数上界：10,000
- 逻辑文件数上界：20 × 10,000 = **200,000**

### 平均吞吐

| 指标 | 计算过程 | 结果 |
|---|---|---|
| 平均字节吞吐 | 5 × 1024 × 1024 KiB ÷ 86,400 s = 5,242,880 ÷ 86,400 | **60.7 KiB/s ≈ 0.059 MiB/s** |
| 平均文件吞吐 | 1,000 ÷ 86,400 | **0.0116 文件/s ≈ 1 文件 / 86.4 s** |
| 单文件平均上传时长（单流 0.5 MiB/s） | 5.12 MiB ÷ 0.5 MiB/s | **≈ 10.2 s** |
| 单机理论上限（8 并发 × 0.5 MiB/s） | 4 MiB/s × 86,400 s ÷ 1024 | **≈ 337 GiB/天 ≈ 67 × 当前日量** |
| 单机保守下限（8 并发 × 0.1 MiB/s） | 0.8 MiB/s × 86,400 ÷ 1024 | **≈ 67.5 GiB/天 ≈ 13.5 × 当前日量** |

### 峰值突发

假设（基线未定义业务窗口，取保守假设）：

- 场景 A：80% 日量落在 2 小时业务窗口
- 场景 B（极端）：100% 日量落在 1 小时窗口

| 场景 | 字节吞吐 | 文件吞吐 |
|---|---|---|
| 场景 A | 5 × 1024 × 80% = 4,096 MiB ÷ 7,200 s | **0.569 MiB/s ≈ 583 KiB/s** |
| 场景 A | 800 ÷ 7,200 | **0.111 文件/s ≈ 1 文件 / 9.0 s** |
| 场景 B | 5,120 MiB ÷ 3,600 s | **1.42 MiB/s** |
| 场景 B | 1,000 ÷ 3,600 | **0.278 文件/s ≈ 1 文件 / 3.6 s** |

峰值/平均倍数：场景 A = 0.569 ÷ 0.059 ≈ **9.6×**；场景 B = 1.42 ÷ 0.059 ≈ **24×**。

结论：当前规模的峰值远低于 8 并发的处理能力（NFR-13 目标 1.5 MiB/s 已覆盖场景 B 的 1.42 MiB/s）。

### 积压（backlog）上界

- 场景 A 的 4 GiB / 800 文件，在 4 MiB/s 聚合处理能力下需 4,096 ÷ 4 = 1,024 s ≈ **17 分钟**清空。
- 因此瞬时 backlog 上界 ≈ 800 个任务，全部驻留 DB（不驻留 JVM，见 NFR-22）。
- 极端故障积压：Gateway 完全不可用 30 天 → 30,000 个 `WAITING_RETRY` 任务堆积，仍在 365,000/年 的量级内，DB 无容量压力；压力在重试速率（见【架构问题】NFR-A1）。

### 1 年数据量与 DB 容量

| 表 | 行数/年（计算过程） | 单行估算 | 数据大小 |
|---|---|---|---|
| `file` | 上界 200,000；按 50% 为新增路径估算 ≈ 182,500 | 250 B | ≈ 45 MB |
| `file_version` | 1,000 × 365 = **365,000** | 200 B | ≈ 73 MB |
| `transfer_task` | 1 : 1 于 `file_version` = **365,000** | 350 B | ≈ 128 MB |
| `transfer_attempt` | 每任务至少 2 次（1 次 S3 + 1 次 Gateway）= 730,000；加 10% 任务额外重试 1 次 = +73,000 → **≈ 803,000** | 450 B | ≈ 361 MB |
| `transfer_event` | 每次迁移 1 条，按 7 次迁移/任务 = 365,000 × 7 = **2,555,000** | 250 B | ≈ 639 MB |
| **表数据合计** | | | **≈ 1,246 MB ≈ 1.22 GiB** |
| 索引（按表数据 40% 估算） | | | ≈ 500 MB |
| 膨胀与 autovacuum 余量（按 50%） | | | ≈ 600 MB |
| **1 年 DB 实际占用** | 1,246 + 500 + 600 | | **≈ 2.3 ~ 2.5 GiB** |
| **规划磁盘** | 预留约 4 年 + 运维余量 | | **10 GiB** |

### 索引量级

| 索引 | 类型 | 年索引项数 |
|---|---|---|
| `uk_file_path` | UNIQUE | 182,500 |
| `uk_file_version_fingerprint` | UNIQUE | 365,000 |
| `uk_file_version_no`（基线 A2 建议） | UNIQUE | 365,000 |
| `uk_task_file_version` | UNIQUE | 365,000 |
| `idx_task_ready`（claim） | 普通 | 365,000 |
| `idx_task_lease`（stale 恢复） | 普通 | 365,000 |
| `idx_task_file_version`（基线 A3 建议） | 普通 | 365,000 |
| `uk_attempt` | UNIQUE | 803,000 |
| `idx_attempt_task` | 普通 | 803,000 |
| `uk_space_code` / `uk_directory_path` | UNIQUE | 20 + 20 |
| **合计** | | **≈ 4.0 M 索引项，索引总大小 ≈ 400 ~ 600 MiB** |

B-tree 高度：约 400 万索引项、8 KB 页、每页约 200 项 → 树高 ≈ 3 层，claim 查询为索引扫描 + `SKIP LOCKED`，满足 NFR-17 的 P95 ≤ 50ms。

### 结论

- 容量瓶颈**不在 PostgreSQL**，而在 NAS IO、S3 连接与 Gateway 并发（与整体设计 §81 一致）。
- 单目录 10,000 文件、单机 20 目录、200,000 逻辑文件上界对 PostgreSQL 无压力。
- 索引数量控制在 11 个以内，遵守整体设计 §83"不要过度索引"。

---

## 并发上限目标

### 并发配置（权威值）

| 层级 | 配置 | 值 | 说明 |
|---|---|---|---|
| 全局上传并发 | `worker.upload.maxConcurrency` | 8 | Semaphore 控制 |
| 全局 Gateway 并发 | `worker.gateway.maxConcurrency` | 8 | Semaphore 控制，与上传可区分 |
| 目录级并发 | `directory.max_concurrency` | 2 ~ 4 | 目录维度 Semaphore |
| 有效并发 | 计算式 | `min(阶段全局并发, 目录并发)` | 再叠加 Semaphore 兜底 |
| 队列容量 | `worker.queue-capacity` | 16（= 2 × 单阶段 max-concurrency） | 有界队列，队列满则停止 claim（背压） |

### 有效并发计算示例

| 目录配置 | 全局上限 | 目录上限 | 有效并发 | 说明 |
|---|---|---|---|---|
| 目录 A | 8 | 4 | 4 | 目录限制更严 |
| 目录 B | 8 | 2 | 2 | 目录限制更严 |
| 目录 C | 8 | 4 | 4 | 目录限制更严 |
| 多目录同时运行 | 8 | 2~4 | ≤ 8 | 全局 Semaphore 兜底 |
| 单目录独占 | 8 | 4 | 4 | 不会突破目录上限 |

### 资源占用上界论证

| 资源 | 上界 | 推导 |
|---|---|---|
| NAS IO（SMB/CIFS 读流） | ≤ 8 个并发顺序读流；单流 ≤ 10 MB；读缓冲 8 KB ~ 1 MB | 上传并发上限 8；单文件 ≤ 10 MB，不做多部分、不整体加载 |
| S3 连接 | ≤ 8 个并发 HTTPS PUT；连接池空闲连接 ≤ 8 | 每个 PUT 1 连接，无 Multipart（基线明确不引入） |
| Gateway 请求 | ≤ 8 个并发 HTTP 请求；连接池 ≤ 8 | Gateway 并发上限 8 |
| PostgreSQL 连接 | ≤ 20 | 应用连接池上限 |
| JVM 平台线程 | ≤ 16（upload 8 + gateway 8）+ 调度/IO 线程 ≤ 16 → 总 ≤ 64 | 有界线程池，禁止 `CachedThreadPool` |
| JVM 虚拟线程 | ≤ 24（活跃 8 + 队列 16），载体线程 ≤ 4 | 若采用虚拟线程，必须配 Semaphore |
| Socket | ≤ 8（S3）+ 8（Gateway）+ 20（PG）= 36 | 远低于单机默认 1,024 |
| JVM 内存 | 峰值 ≤ 8 × 1 MiB 缓冲 + 24 × 1 KB 任务元数据 ≈ 8 MB；堆 ≤ 2 GiB | 流式读取，不缓存整个文件 |
| 单任务执行时长上界 | ≤ 4 分 35 秒 < `lease.duration`(5m) | 见"延迟与超时预算"约束 C3 |

**结论**：并发上界由三层保护叠加——全局 Semaphore(8) → 目录 Semaphore(2~4) → 有界队列(≤16，满则背压停止 claim)。
任何时刻系统对 NAS / S3 / Gateway / JVM 的占用都是有界的，不会出现"1000 文件集中到达 → 资源爆炸"。

---

## 延迟与超时预算

### 预算表

| 项目 | 配置键 | 值 | 说明 |
|---|---|---|---|
| 扫描周期范围 | `directory.scanInterval` | 5m ~ 24h | 基线 §2.3："几分钟到数小时甚至一天" |
| 稳定确认延迟 | 推导 | 2 × `scanInterval`，即 **10m ~ 48h** | 相邻两次观察 `path + size + mtime` 一致 |
| 调度周期（Dispatcher / Recovery / Retry） | `scheduler.interval` | 30s ~ 60s，默认 **60s** | 小批量拉取 + 有界队列 |
| lease 时长 | `lease.duration` | **5m** | 基线权威值 |
| lease 续租间隔 | `lease.renewInterval` | **100s** = `lease.duration / 3` | 基线 §6 建议 |
| S3 连接超时 | `s3.connectTimeout` | **10s** | |
| S3 单次请求超时 | `s3.putTimeout` | **3m** | 覆盖 10 MB @ 33 KiB/s（≈ 310s） |
| Gateway 连接超时 | `gateway.connectTimeout` | **5s** | |
| Gateway 读超时 | `gateway.readTimeout` | **60s** | |
| retry 初始延迟 | `retry.initialDelay` | **30s** | 基线权威值 |
| retry 倍率 | `retry.multiplier` | **2** | 序列 30s → 1m → 2m → 4m → 8m → 16m → 32m → 1h |
| retry 最大延迟 | `retry.maxDelay` | **1h** | 超过后固定 1h，永不停止 |
| retry 抖动 | `retry.jitter` | **±20%** | |
| supersede 宽限期 | `supersede.gracePeriod` | **15m** | 基线权威值 |
| supersede 失败阈值 | `supersede.afterFailedAttempts` | **5** | |
| supersede 等待阈值 | `supersede.afterWaiting` | **30m** | |

### 超时之间的约束关系（必须同时成立）

| 编号 | 约束 | 校验 | 目的 |
|---|---|---|---|
| C1 | `s3.putTimeout`(3m) < `lease.duration`(5m) | 3m < 5m ✓ | 单次 S3 PUT 不可能跨越 lease 过期，避免恢复 worker 与原 worker 并发 PUT 同一 Object |
| C2 | `gateway.readTimeout`(60s) < `lease.duration`(5m) | 60s < 5m ✓ | 单次 Gateway 调用不会跨 lease |
| C3 | `T_task` = `s3.putTimeout`(3m) + `gateway.connectTimeout`(5s) + `gateway.readTimeout`(60s) + 本地 IO/DB 开销(≤30s) ≈ **4m35s** < `lease.duration`(5m) | 4m35s < 5m ✓ | **单个任务的一次完整执行不会跨越 lease**，从根本上避免同版本并发执行 |
| C4 | `supersede.gracePeriod`(15m) ≥ `gateway.readTimeout`(60s) + `lease.duration`(5m) + 5m = 11m | 15m ≥ 11m ✓（余量 4m） | 关闭"Gateway 在途请求与 S3 覆盖"的竞态窗口（基线 §3.5） |
| C5 | `scheduler.interval`(60s) ≤ `lease.duration`(5m) | 60s ≤ 5m ✓ | 恢复检查频率足够，且不会在同一 lease 窗口内重复触发恢复 |
| C6 | `retry.maxDelay`(1h) ≥ 60 × `gateway.readTimeout`(60s) | 1h ≥ 1h ✓ | 重试间隔远大于单次调用耗时，避免重试风暴 |
| C7 | `supersede.afterWaiting`(30m) ≥ `supersede.gracePeriod`(15m) | 30m ≥ 15m ✓ | "等待阈值"不早于宽限期生效 |
| C8 | `gateway.connectTimeout`(5s) < `gateway.readTimeout`(60s) | 5s < 60s ✓ | 连接与读取超时分层 |
| C9 | `s3.connectTimeout`(10s) < `s3.putTimeout`(3m) | 10s < 3m ✓ | 连接与请求超时分层 |

### 端到端延迟分解（正常依赖）

| 阶段 | 典型时长 | 上界 |
|---|---|---|
| 发现 → 稳定（2 次观察） | 1 × 扫描周期 | 2 × 扫描周期（10m ~ 48h） |
| `READY` → claim | ≤ 1 个调度周期 | 60s |
| S3 上传 | 10.2s（5.12 MiB @ 0.5 MiB/s） | 3m（`s3.putTimeout`） |
| `S3_UPLOADED` → `GATEWAY_DELIVERING` | ≤ 1 个调度周期 | 60s |
| Gateway 投递 | ≤ 5s | 60s（`gateway.readTimeout`） |
| **`stable_at` → `completed_at` 合计** | ≈ 35s | ≈ 5m35s |

NFR-15 的 P95 ≤ 10 分钟已包含 DB 抖动与队列等待余量。

### 稳定确认延迟的关键约束

- 对 `scanInterval = 6h` 的目录：稳定确认 ≤ 12h。
- 对 `scanInterval = 24h` 的目录：稳定确认 ≤ **48h**——这与 NFR-15（`stable_at` 后 P95 ≤ 10 分钟）不冲突（NFR-15 从稳定时刻起算），但与业务"尽快处理"的期望存在张力，见【架构问题】NFR-A3。

---

## 可恢复性目标

### RPO / RTO 定义

| 术语 | 定义 | 目标 |
|---|---|---|
| **RPO（Recovery Point Objective）** | 崩溃后允许丢失的数据量（时间维度） | **任务层 RPO = 0**（已落库的 `file_version`/`transfer_task` 不丢）；**数据库层 RPO ≤ 15 分钟**（依赖 WAL 归档/PITR） |
| **RTO（Recovery Time Objective）** | 崩溃后未完成任务恢复执行的最长时间上界 | **单 JVM 崩溃 RTO ≤ 8 分钟**；**整机故障 RTO ≤ 30 分钟** |

### RTO 论证（崩溃后未完成任务最长恢复时间上界 = `lease.duration` + 调度周期）

**定义**：

```text
RTO(单 JVM 崩溃) = lease.duration + 调度周期 + 启动与恢复扫描时间
                 = 5m + 1m + ≤2m
                 = ≤ 8m
```

**论证**：

1. 崩溃恢复的触发条件是 `lease_until < now()`。最坏情况是崩溃恰好发生在 lease 刚被续租之后，因此必须等完整的 `lease.duration`（5 分钟）才能判定为 stale task。
2. stale task 的发现频率由调度周期决定（默认 60s，最大 60s），因此从"lease 过期"到"恢复动作开始"≤ 1 个调度周期。
3. 恢复动作本身是对 `UPLOADING`/`S3_UPLOADED`/`GATEWAY_DELIVERING` 任务的条件更新（基线 §7 T10/T12/T16），在 1,000 条 stale task 以内的规模下 ≤ 2 分钟（含 JVM 启动、DB 连接、Flyway/校验）。
4. 三项相加即上界 8 分钟。该上界**不依赖**崩溃发生的时间点，因为 lease 一旦被续租，其剩余时间必然 ≤ `lease.duration`。

**按崩溃点细分**（对应基线 §7 与整体设计 §64）：

| 崩溃点 | 崩溃时状态 | 恢复动作 | 恢复时间上界 |
|---|---|---|---|
| S3 上传中 `kill -9` | `UPLOADING` | lease 过期 → `READY`（T10）→ 重传（覆盖同一 Object） | ≤ 8m |
| S3 成功但 DB 未提交即崩溃 | `UPLOADING` | 同上一行，允许覆盖 | ≤ 8m |
| S3 已提交后崩溃 | `S3_UPLOADED` | lease 过期 → `GATEWAY_DELIVERING`（T12） | ≤ 8m |
| Gateway 响应未收到即崩溃 | `GATEWAY_DELIVERING` | lease 过期 → `WAITING_RETRY`（T16）→ 重投 | ≤ 8m + `next_retry_at`（≤ 1h） |
| Gateway 长期失败 | `WAITING_RETRY` | 指数退避 → `GATEWAY_DELIVERING`（T17），**永不停止** | 无上界（业务要求"永不放弃"） |

### 与 supersede 的联合上界

最新稳定版本的最坏等待时间 = 旧版本占位/退避到「可被 supersede」的时长 + 一个调度周期：

```text
T_worst = lease.duration                              (旧版本活跃执行最长占位，5m)
        + max(supersede.afterWaiting,                 (按等待时间触发，30m)
              supersede.afterFailedAttempts × 退避上界)  (按失败次数触发：5 × maxDelay(1h) = 5h)
        + 一个 SupersedeService 调度周期
默认值代入：5m + max(30m, 5h) + 调度周期 ≈ 5~6 小时
```

即：被旧版本阻塞的新版本，最坏在约 **5~6 小时**内获得执行权（与 `04-state-machine.md` §6.4、`13-open-questions-risks.md` §3.4 一致）。

> **更紧上界的说明**：对「从未执行、仅 `READY`/`DISCOVERED`/`STABILITY_CHECK` 排队」的旧版本，退避项为 0，上界收紧为
> `lease.duration + max(gracePeriod, afterWaiting) + 一个调度周期`（`5m + max(15m, 30m) + 1m ≈ 36 分钟`）。
> 这是无退避子情形的更紧界，**不改变**对外承诺的最坏 5~6 小时。

### 恢复正确性要求

- 恢复动作必须使用条件更新：`WHERE id=? AND status=? AND lease_until < now()`，避免覆盖仍在运行的 worker。
- 启动顺序强制：DB 连接 → Recovery（stale lease）→ Retry/Dispatcher + Worker → Scanner（最后启动）。
- 优雅关闭顺序：停止新扫描 → 停止 claim → 等待在途 worker（有超时）→ 尽量完成当前上传 → 释放 lease → 退出。

---

## 明确不追求的目标

| 不追求的目标 | 原因 | 若必须达到的代价 |
|---|---|---|
| **Exactly-once 投递** | S3 Object Key 不含版本、新版本覆盖旧对象；Gateway 幂等键与成功响应契约未定义（基线 Q3）；分布式 exactly-once 需要端到端幂等 + 事务性 outbox + 确认协议。业务已明确接受 At-least-once（允许重复、不允许丢失） | 需 Gateway 提供幂等键 + 回执查询接口 + 去重表 + 投递确认状态机；显著增加复杂度与跨系统耦合 |
| **跨系统 ACID / 分布式事务** | NAS、S3、Gateway 三方无法纳入同一事务；V1 明确不引入分布式事务 | 需引入 TCC/Saga/2PC 或 MQ，违背基线"明确不引入"清单 |
| **99.99% 可用性（年停机 ≤ 52.6 分钟）** | 单机部署（单 JVM + 单 PostgreSQL），无 HA、无自动故障转移；扫描调度缺 DB 级互斥（基线 A8），多实例会重复扫描 | 需多实例 + PostgreSQL HA + 分布式扫描互斥 + 负载均衡，属于 V5 演进范围 |
| **亚秒级端到端延迟** | 最小扫描周期 5m，且稳定判定需要连续两次观察，端到端下界 = 2 × 扫描周期；业务是批量文件中转，非实时流 | 需文件系统事件监听（inotify）+ 取消稳定双观察，与"文件写入中不得上传"的可靠性要求冲突 |
| **"尽可能快"的无界并发** | 无界并发会导致 SMB/NAS IO 爆炸、S3 连接池爆炸、Gateway 请求爆炸、JVM 线程/socket 激增（整体设计 §44） | 需为 NAS/S3/Gateway 扩容并提供端到端限流，违背单机规模前提 |
| **S3 对象版本控制** | 基线明确不引入；新版本直接覆盖同一 Object | 需额外管理版本清单与生命周期策略，与 supersede 语义重复 |
| **大文件 / Multipart 上传** | 单文件 ≤ 10 MB，单次 PUT 足够；Multipart 属 V4 演进范围 | 需分片、断点续传、分片合并与清理，V1 收益为 0 |
| **文件内容级校验（SHA-256）** | 基线明确不做内容 hash，`fingerprint = SHA-256(relative_path + size + mtime)` 不读文件内容；单文件 ≤ 10 MB 但内容校验会显著增加 NAS 读取量 | 每次扫描需读取全部文件内容，NAS IO 放大 1000×/天 |
| **跨文件 / 全局投递顺序** | 只保证同一 `file_id` 路径串行（不变量 I2），不同文件并行 | 需全局串行化，吞吐降为 1，违背并发设计 |
| **零重复投递** | At-least-once 的固有代价；超时/结果未知一律按失败处理并重投 | 需 Gateway 幂等 + 去重，等同 exactly-once |
| **应用日志入 PostgreSQL** | 基线 §55 明确不做；日志走 stdout → Loki/ELK/Splunk | 会把 INFO/DEBUG/stacktrace 塞进 DB，污染业务表并放大容量 |
| **实时 Dashboard / Admin 前端** | 基线明确不引入 Admin 前端；运维通过 SQL + Prometheus 查询 | 需前端与 API 层，属 V2 演进范围 |
| **亚秒级告警** | 告警评估周期按 Prometheus 抓取间隔 15s、评估周期 ≤ 1m 设计，时延目标 ≤ 2 分钟 | 需事件驱动告警链路，收益有限 |

---

## 【架构问题】

### 【架构问题】NFR-A1：长期依赖故障下的"重试风暴"

- **问题**：基线规定"失败任务永不自动删除、无 `FAILED` 终态、永久重试直到成功"，且 `retry.maxDelay = 1h`。当 Gateway（或某客户 SFTP）长时间不可用时，非终态任务在 1 年后可达 365,000 个，每个任务每小时重试 1 次 → **约 101 次请求/秒**，而 `worker.gateway.maxConcurrency = 8` 只能支撑个位数 QPS 的持续投递。
- **风险**：① 重试请求在 DB 中持续占用 claim 扫描与连接池；② Gateway 侧被重试流量压垮，恢复后仍无法自愈；③ `idx_task_ready` 上的 `WAITING_RETRY` 行数膨胀导致 claim 查询退化；④ 运维无法区分"真实业务失败"与"系统性故障"。
- **建议方案**：新增**全局重试速率上限**（如 `retry.globalRateLimit = 8 次/秒`）与**按 customerSpace 的熔断/降级**（连续失败 N 次后暂停该客户空间的新投递，保留 backlog）；保留 `maxDelay = 1h` 与"永不删除"语义不变（只限流，不放弃）；补充 backlog 深度与重试速率的告警阈值。
- **对现有设计的影响**：需在 `retry`/`worker` 模块增加一个全局 RateLimiter 与熔断开关；状态机与迁移矩阵**不需要改动**（任务仍停留在 `WAITING_RETRY`，只是被限流延后执行）；需要新增配置项与 2 个 Metrics（`retry_throttled_total`、`backlog_waiting_retry`）。

### 【架构问题】NFR-A2：`supersede.gracePeriod` 默认值与计算公式不自洽

- **问题**：基线 §3.1 定义 `gracePeriod = gateway.readTimeout + lease.duration + 5m` 且"默认值 15m"。代入 `lease.duration = 5m` 后，只有当 `gateway.readTimeout = 5m` 时该式才等于 15m；而若 `gateway.readTimeout = 5m`，则 `gateway.readTimeout` 与 `lease.duration` 相等，违反本文约束 C2（`lease > gateway.readTimeout`），也削弱 C3（单任务执行不跨 lease）的保证。
- **风险**：若实现者按公式取 `readTimeout = 5m`，单次 Gateway 调用 + S3 PUT 可能接近甚至超过 5m 的 lease，导致 lease 过期后恢复 worker 与原 worker 并发操作同一任务；若取 `readTimeout = 60s`，则公式值为 11m ≠ 15m，默认值 15m 变成"无来源的魔数"。
- **建议方案**：把 `gracePeriod` 明确定义为**下界约束**而非等式：`supersede.gracePeriod ≥ gateway.readTimeout + lease.duration + 5m`，默认取 `max(15m, 该下界)`；本文取 `gateway.readTimeout = 60s` → 下界 11m，默认 15m 满足并留 4m 余量。
- **对现有设计的影响**：仅影响配置项语义与文档表述，不改变 supersede 算法（基线 §3.3 三条判定条件保持不变）；建议在配置校验中增加"启动时断言 C1/C2/C3/C4"的 fail-fast 检查。

### 【架构问题】NFR-A3：24h 扫描周期目录的稳定确认延迟达 48h

- **问题**：稳定判定要求"相邻两次观察 `path + size + mtime` 完全一致"（基线 §2/T4），而 `scanInterval` 允许到 24h。因此 24h 周期目录的文件从"写完毕"到"进入 `READY`"最坏需要 **48 小时**；整体设计 §2.3 又要求"扫描到时间后尽快处理该目录中所有待处理文件"。
- **风险**：① 业务侧"尽快处理"的期望与 48h 延迟直接冲突；② 该延迟远超本文 NFR-15 的端到端目标（虽然 NFR-15 从 `stable_at` 起算，但业务视角的延迟是"文件出现 → 客户收到"）；③ 24h 目录的 backlog 会在单次扫描后集中爆发，瞬时并发压力最大（这也是 NFR-22 背压设计的主要触发场景）。
- **建议方案**：与基线开放问题 **Q1** 一致，引入独立的 `stabilityRecheckInterval`（建议 5m ~ 15m）：文件首次发现后按该间隔快速重查 1~2 次完成稳定判定，`scanInterval` 只决定"全量发现"的周期。这样稳定确认延迟与扫描周期解耦，24h 目录的稳定确认也可收敛到 ≤ 30 分钟。
- **对现有设计的影响**：需在 `scanner` 模块增加一个独立的稳定性重查调度（复用同一套 `file_version` 状态与 T2/T3/T4 迁移，**不新增状态**）；会增加对 NAS 的 stat 频率（每个待稳定文件多 1~2 次 stat），需纳入 NFR-16 的 stat 速率预算；属配置与调度层改动，不触碰状态机与 supersede 规则。

### 已知并接受的风险（非新问题，引用基线）

| 引用 | 摘要 | 对本文的影响 |
|---|---|---|
| 基线 A1 | Gateway 在途请求与 S3 覆盖的竞态无法完全消除，靠宽限期收敛 | NFR-47 只要求"重复投递 ≤ 1 次/task"，不承诺零重复 |
| 基线 A7 | 1 年保留策略与"失败任务永不删除"冲突 | NFR-36/NFR-37 明确"非终态永不清理"，终态才受 1 年约束 |
| 基线 A8 | 未来多实例下扫描调度缺 DB 级互斥 | NFR-07 的 99.5% 目标基于单机；水平扩展属 V5 |
| 基线 Q1 | 扫描周期 24h 的目录稳定确认是否需等一整个周期 | 见【架构问题】NFR-A3 |
| 基线 Q3 | Gateway 认证方式、成功响应契约、幂等键未定义 | 影响 NFR-03/NFR-35 的可验证性，需在接口契约确认后补充 |

---

## 验证方式索引（Phase 1+ 落地用）

| 验证手段 | 覆盖的 NFR |
|---|---|
| Testcontainers 集成测试（PostgreSQL） | NFR-01、NFR-04、NFR-05、NFR-06、NFR-17、NFR-24、NFR-36 ~ NFR-41 |
| `kill -9` 故障注入脚本 + 重启 | NFR-02、NFR-03、NFR-42、NFR-44、NFR-47、NFR-49 |
| 依赖故障注入（mock Gateway 503/401、`docker pause` PostgreSQL、卸载 NAS） | NFR-09、NFR-10、NFR-11、NFR-35 |
| 压测脚本（15 GiB / 3000 文件、2h 峰值窗口） | NFR-12、NFR-13、NFR-14、NFR-18 |
| Prometheus 查询（`histogram_quantile`、`rate`、`up`、`ALERTS`） | NFR-07、NFR-14、NFR-15、NFR-27、NFR-28 |
| 日志断言（字段正则 + 敏感信息扫描） | NFR-26、NFR-30、NFR-31、NFR-50 |
| DB 断言（不变量 I2/I6/I7、行数与容量） | NFR-05、NFR-06、NFR-37、NFR-39、NFR-40、NFR-44 |
| 静态检查（ArchUnit / grep：无界线程池、NAS 写 API、明文密钥） | NFR-23、NFR-31、NFR-33 |
| 权限矩阵测试（受限 S3/DB 凭据执行越权操作） | NFR-34 |
| 备份恢复演练（季度） | NFR-45、NFR-48 |
| 配置校验 fail-fast（断言 C1 ~ C9） | 全部超时约束 |

---

## 验收对齐

本文所有 NFR 与整体设计 §92 V1 验收标准一一对应：

| 整体设计 §92 验收项 | 对应 NFR |
|---|---|
| 正常流程 NAS → S3 → Gateway → SFTP | NFR-01、NFR-15 |
| 文件写入中不能上传 / 两次稳定 | NFR-04 |
| 文件删除 / 文件变化（v1 → v2） | NFR-05、NFR-37 |
| S3 失败 / Gateway 失败 / Gateway timeout | NFR-09、NFR-35 |
| Gateway success + response lost | NFR-03、NFR-47 |
| JVM `kill -9` during S3 / during Gateway | NFR-02、NFR-42、NFR-44 |
| Scanner 重复执行 | NFR-06 |
| 同文件 v1/v2 不能并行 / 不同文件可以并行 | NFR-05、NFR-19、NFR-20、NFR-24 |
| 配置修改旧任务不受影响 | NFR-08 |
| Gateway 永久不可用，任务不能消失 | NFR-09、NFR-37、【架构问题】NFR-A1 |
