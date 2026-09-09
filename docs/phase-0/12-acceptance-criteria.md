# 12 Acceptance Criteria（验收标准）

## 本部分范围

本部分把基线、`03-business-rules.md`（BR-01–BR-65）与 `04-state-machine.md`（T1–T23）中已经确定的**业务规则、状态迁移、不变量**，逐条翻译成**客观可验证**的验收标准（`AC-xx`）。

- 覆盖 **11 个模块**：scanner、file/stability、task、worker/claim、S3、Gateway、retry、recovery、audit、observability、manual retry。
- 覆盖 **5 个跨模块验收维度**：可靠性、一致性、可恢复、可追踪、可运维。
- 每条 AC 均给出：编号、验收项、前置条件/测试数据、**具体命令或可复现步骤**、**可断言的期望结果**、不通过判定。
- 状态集合严格为基线 §1 的 **9 个**（`DISCOVERED`、`STABILITY_CHECK`、`READY`、`UPLOADING`、`S3_UPLOADED`、`GATEWAY_DELIVERING`、`WAITING_RETRY`、`DELIVERED`、`CANCELLED`），**不新增** `FAILED` / `CLAIMED` / `PROCESSING` / `SUPERSEDED`。
- 本部分共 **84 条 AC**（模块 62 条 + 维度 22 条）。

## 本部分不做什么

- 不写 Java 代码、不写 DDL、不创建 `*.java` / `*.sql` / `pom.xml` / `mvnw`；文中的 SQL 片段均标注「示意，非 DDL 交付物」。
- 不定义状态迁移语义本身（权威见 `04-state-machine.md`），只做「如何验证」。
- 不定义配置项默认值（见 `07-configuration-model.md`）、不定义指标埋点实现（见 `11-observability-model.md`）。
- 不改变已确认架构；发现冲突只标注 `【架构问题】`。

## 对应整体设计文档章节

| 整体设计章节 | 本部分对应内容 |
|---|---|
| §69 关键不变量 | 各模块 AC 的不变量断言（I1–I8） |
| §86 故障场景矩阵 | 可靠性验收、recovery 验收、E2E-2 |
| §92 V1 验收标准 | 端到端验收场景（E2E）、各模块主链路 AC |
| §9–§12 稳定性/文件消失 | scanner、file/stability |
| §13–§16 状态机 / Claim / Lease | task、worker/claim、一致性验收 |
| §17 Crash Recovery | recovery、可恢复验收 |
| §19–§21 同路径串行 / 版本一致性 | 一致性验收、E2E-3 |
| §24–§26 超时 / Retry | Gateway、retry |
| §40–§41 S3 Object Key / 上传 | S3 |
| §48–§51 Gateway Worker / 人工重试 | Gateway、manual retry |
| §52 / §57 / §60 审计与可追踪 | audit、可追踪验收、可运维验收 |
| §54–§56 保留 / 安全 | audit、observability |

---

## 1. scanner 验收标准

| 编号 | 验收项 | 前置条件/测试数据 | 执行动作（具体命令或操作步骤） | 期望可观察结果 | 不通过判定 |
|---|---|---|---|---|---|
| AC-01 | 只发现后缀命中且目录 enabled 的文件（T1、BR-03、BR-07） | NAS 临时目录含 `a.csv`、`b.txt`；另建 `disabled/` 目录含 `c.csv` 且配置 `enabled=false` | `mvn -Dtest=ScannerDiscoveryIT test`；再 `docker exec <pg> psql -U ${PG_USER} -d ${PG_DB} -c "SELECT f.relative_path FROM file_version fv JOIN file f ON f.id=fv.file_id"` | 结果集仅含 `a.csv`；`b.txt` 与 `c.csv` 无 `file_version`；日志 `event=SCAN_DISCOVERED` | `b.txt` 或 disabled 目录文件出现 `file_version` |
| AC-02 | 重复扫描不产生重复 task（T1、BR-10、I6） | 已完成一次扫描，DB 有 1 个 `file_version` | 连续触发两次扫描后 `mvn -Dtest=ScannerIdempotencyIT test`；查询 `SELECT count(*) FROM transfer_task WHERE file_version_id=<id>` | 查询结果为 `1`；第二次 `scanner_files_discovered_total` 不增长；`INSERT ... ON CONFLICT DO NOTHING` 影响 0 行 | 结果 `>1` 或 counter 翻倍 |
| AC-03 | 全目录扫描 + 流式，不整读文件（BR-09） | NAS 目录含 1 个 10MB 文件 | `mvn -Dtest=ScannerStreamingTest test`；静态检查 `grep -rn "readAllBytes" src/main/java/com/example/transfer/scanner` | grep 无匹配；扫描期间未打开文件内容流；堆峰值增量 `< 5MB` | 出现 `readAllBytes` 或扫描时读取文件内容 |
| AC-04 | 单目录扫描失败不永久停止（BR-12） | 将某目录设为不可读，或 NAS 桩抛 `IOException` | 触发扫描（观察失败）→ 恢复权限 → 触发下一周期扫描；`curl -s localhost:8080/actuator/prometheus | grep scanner_scan_failure_total` | `scanner_scan_failure_total{error_type=...}` +1；下周期 `scanner_scan_success_total` +1；日志 `event=SCAN_FAILED` | 后续周期不再扫描该目录（success 计数恒不增长） |
| AC-05 | 首次发现落库失败仅跳过本周期（T1） | 注入 DB 唯一冲突 / 临时异常 | `mvn -Dtest=ScannerDbFailureIT test`；查询 `SELECT count(*) FROM file_version WHERE fingerprint=<fp>` | 本周期无脏记录，下周期成功创建；`file_version` 计数最终为 `1` | 出现半写记录或下周期仍失败 |
| AC-06 | 文件逻辑身份唯一（BR-08、`UNIQUE(nas_id,directory_id,relative_path)`） | 同一相对路径被两线程并发扫描 | `mvn -Dtest=ScannerConcurrencyIT test`；查询 `SELECT count(*) FROM file WHERE relative_path=<p>` | `file` 表该路径恰好 `1` 行；第二线程落库影响 0 行 | `file` 表出现 2 行 |

---

## 2. file/stability 验收标准

| 编号 | 验收项 | 前置条件/测试数据 | 执行动作（具体命令或操作步骤） | 期望可观察结果 | 不通过判定 |
|---|---|---|---|---|---|
| AC-07 | 首次观察进入 STABILITY_CHECK（T2、BR-14） | 新文件 `x.csv` 首次被扫描 | `mvn -Dtest=StabilityFirstObservationIT test`；查询 `SELECT status FROM transfer_task WHERE file_version_id=<id>` | `status=STABILITY_CHECK`；`transfer_event` 有 `DISCOVERED→STABILITY_CHECK` | 停留 `DISCOVERED` 或直接 `READY` |
| AC-08 | 相邻两次 path+size+mtime 完全一致 → READY（T4、BR-14） | 文件写入完成，两次扫描间 `size`/`mtime` 不变 | `mvn -Dtest=StabilityTwoConsistentObservationsIT test`；查询 `SELECT status, stable_at FROM ...` | `status=READY`、`stable_at` 非空；`scanner_files_stable_total` +1 | 未进入 `READY` 或 `stable_at` 为空 |
| AC-09 | fingerprint 变化重置稳定基线（T3、BR-16） | 第二次扫描前向文件追加字节，`size` 变化 | `mvn -Dtest=StabilityResetIT test`；查询 `SELECT status FROM ...` | 仍为 `STABILITY_CHECK`；未产生新 task；`scanner_files_stable_total` 不增长 | 进入 `READY` 或产生重复 task |
| AC-10 | fingerprint = SHA-256(relative_path+size+mtime)，非内容 hash（BR-15） | 固定 `(path,size,mtime)` 输入 | `mvn -Dtest=FingerprintTest test`；检查扫描期无文件内容读取调用 | 计算结果等于预置期望值；扫描期无 `InputStream` 打开业务文件 | 计算依赖文件内容或结果与期望不符 |
| AC-11 | 稳定前文件消失 → CANCELLED（T5、BR-51） | 任务处于 `STABILITY_CHECK` 时从 NAS 删除文件 | 删除文件 → 触发扫描 → `mvn -Dtest=StabilityFileGoneIT test`；查询 `SELECT status, cancel_reason FROM ...` | `status=CANCELLED`、`cancel_reason=FILE_MISSING`；`scanner_files_cancelled_total{reason=FILE_MISSING}` +1 | 停留 `STABILITY_CHECK` 或进入 `READY` |
| AC-12 | 未稳定不得进入上传（BR-18，非法迁移） | 任务 `STABILITY_CHECK` | `mvn -Dtest=StateMachineIllegalTransitionIT test`（尝试 `transition(task, UPLOADING)`） | 抛出状态机异常；`status` 不变；WARN 日志 + `transfer_event`（rejected） | 迁移成功或状态被改写 |

---

## 3. task 验收标准

| 编号 | 验收项 | 前置条件/测试数据 | 执行动作（具体命令或操作步骤） | 期望可观察结果 | 不通过判定 |
|---|---|---|---|---|---|
| AC-13 | 一个 file_version 最多一个 task（BR-23、I1、`UNIQUE(file_version_id)`） | 同一 `file_version` 并发创建任务 | `mvn -Dtest=TaskUniquenessIT test`；查询 `SELECT count(*) FROM transfer_task WHERE file_version_id=<id>` | 结果为 `1`；并发插入时违反唯一约束抛 `DataIntegrityViolation` | 出现 `>1` 行 |
| AC-14 | file+file_version+task 同事务（BR-25） | 在 task 插入后强制抛异常 | `mvn -Dtest=TaskAtomicityIT test`；查询孤儿 `SELECT count(*) FROM file_version fv LEFT JOIN transfer_task t ON t.file_version_id=fv.id WHERE t.id IS NULL` | 事务回滚，孤儿计数为 `0` | 出现孤儿 `file_version` 或孤儿 task |
| AC-15 | 任务创建固化配置快照（BR-26） | 已创建任务，随后修改 `s3_object_key` / `gateway_route` 配置 | 创建任务后改配置，再 `SELECT s3_object_key, gateway_route, config_version FROM transfer_task WHERE id=<id>` | 快照列与创建时一致，未被新配置覆盖 | 快照列随配置变更 |
| AC-16 | 配置修改只影响新任务（BR-27） | 存在旧 `READY` 任务 | 修改配置并重启，等待旧任务执行；对比新旧任务的快照列 | 旧任务使用旧快照，新任务使用新快照 | 旧任务目标漂移 |
| AC-17 | task 冗余 `file_id`/`version_no` 且不可变（A3、BR-20） | 已创建任务 | 查询 `SELECT t.file_id, t.version_no, fv.file_id, fv.version_no FROM transfer_task t JOIN file_version fv ON fv.id=t.file_version_id WHERE t.id=<id>`；再尝试 `UPDATE transfer_task SET version_no=...` | 两表 `file_id`/`version_no` 一致；`UPDATE` 被拒或影响 0 行 | 值不一致或可被改写 |

---

## 4. worker/claim 验收标准

| 编号 | 验收项 | 前置条件/测试数据 | 执行动作（具体命令或操作步骤） | 期望可观察结果 | 不通过判定 |
|---|---|---|---|---|---|
| AC-18 | claim 用 `FOR UPDATE SKIP LOCKED` 且同事务写 lease（T6、BR-48、BR-49） | 1 个 `READY` 任务，2 个 worker 并发 | `mvn -Dtest=TaskClaimIT test`；抓日志 `grep TASK_CLAIMED logs/app.log` | 同一 task 仅一个 worker claim；`TASK_CLAIMED` 仅 1 条；`worker_id`/`claimed_at`/`lease_until` 非空 | 两个 worker 均 claim 或 lease 字段为空 |
| AC-19 | 串行守卫阻止更晚版本（BR-28、T6 守卫） | v1 `READY` 非终态，v2 已 `READY` | `mvn -Dtest=SerialGuardIT test`；查询 `SELECT version_no, status FROM ...` | v1 优先被 claim 进入 `UPLOADING`；v2 保持 `READY` | v2 在 v1 非终态时被 claim |
| AC-20 | 全局/目录并发受限（BR-47） | 压入远大于并发上限的任务 | 压测后采样 `curl -s localhost:8080/actuator/prometheus | grep -E "task_uploading|task_delivering"` | 峰值 ≤ `min(worker.upload.maxConcurrency=8, directory.max_concurrency)`（Gateway 阶段同理 ≤ 8） | 峰值超过任一上限 |
| AC-21 | 执行中周期性续租（BR-48） | 1 个长任务执行中 | 执行期间轮询 `SELECT lease_until FROM transfer_task WHERE id=<id>`；抓日志 `grep LEASE_RENEWED` | `lease_until` 单调递增；续租周期 ≈ `lease.duration/3`（约 100s） | `lease_until` 停滞并被他 worker 接管 |
| AC-22 | 有界队列背压（BR-50） | 注入慢任务填满 executor 队列 | 观察队列满时 claim 行为；采样 `task_ready` 与 JVM 内存指标 | 队列满时停止 claim，`task_ready` 在 DB 侧增长而 JVM 内存稳定；无任务丢失 | 队列无界增长 / OOM / 任务丢失 |
| AC-23 | 每次 attempt 独立打开 NAS 文件（BR-39） | 第一次上传失败后触发重试 | `mvn -Dtest=UploadReopenIT test` | 第二次 attempt 重新 `open` 文件，无「stream closed / reuse」异常，最终成功 | 复用失效流导致重试失败 |

---

## 5. S3 验收标准

| 编号 | 验收项 | 前置条件/测试数据 | 执行动作（具体命令或操作步骤） | 期望可观察结果 | 不通过判定 |
|---|---|---|---|---|---|
| AC-24 | Object Key 稳定且不含版本（BR-35） | 输入 `customer-space` 与 `relative-path` | `mvn -Dtest=S3KeyGenerationTest test` | Key 严格等于 `<customer-space>/<relative-path>`，不含 UUID/timestamp/version_no/fingerprint/随机后缀 | Key 含上述任一元素 |
| AC-25 | S3 不启用版本控制（BR-36） | 目标桶 | `aws s3api get-bucket-versioning --bucket ${S3_BUCKET}` | 返回无 VersioningConfiguration 或 `Status: Suspended` | 返回 `Status: Enabled` |
| AC-26 | 上传成功写 `s3_uploaded_at` 并迁移 S3_UPLOADED（T8、BR-37） | S3 桩返回 200 | `mvn -Dtest=S3UploadSuccessIT test`；查询 `SELECT status, s3_uploaded_at FROM transfer_task WHERE id=<id>`；`SELECT outcome FROM transfer_attempt WHERE transfer_task_id=<id> AND attempt_type='S3_UPLOAD'` | `status=S3_UPLOADED`、`s3_uploaded_at` 非空、attempt `outcome=SUCCESS`；`upload_success_total` +1 | `status=DELIVERED`、`s3_uploaded_at` 为空或无 attempt |
| AC-27 | 上传失败 → WAITING_RETRY，`retry_count+1`（T9） | S3 桩返回 500 | `mvn -Dtest=S3UploadFailureIT test`；查询 `SELECT status, retry_count, next_retry_at, s3_uploaded_at FROM ...` | `status=WAITING_RETRY`、`retry_count=1`、`next_retry_at ≈ now+30s`、`s3_uploaded_at IS NULL`；`upload_failure_total` +1；日志 `event=S3_SERVER_ERROR` | `status=FAILED` 或 `s3_uploaded_at` 非空 |
| AC-28 | 流式上传不整读内存（BR-38） | 10MB 文件 | `mvn -Dtest=S3StreamingTest test`，采样上传期堆峰值 | 堆增量远小于文件大小；上传请求体为 `InputStream` | 堆增量 ≈ 文件大小 |
| AC-29 | 重复上传覆盖同一 Object（BR-34、EX-53） | 同一 task 二次上传 | 记录上传前后 `aws s3api head-object --bucket ${S3_BUCKET} --key <key>` 的 `ContentLength`/`ETag` | 同一 Key、`ContentLength` 与 NAS 文件一致、无新增对象数 | 生成新 Key 或对象数增加 |

---

## 6. Gateway 验收标准

| 编号 | 验收项 | 前置条件/测试数据 | 执行动作（具体命令或操作步骤） | 期望可观察结果 | 不通过判定 |
|---|---|---|---|---|---|
| AC-30 | 明确成功 → DELIVERED，同事务写 attempt + `completed_at`（T13、BR-37） | Gateway 桩按成功契约返回 200 | `mvn -Dtest=GatewaySuccessIT test`；查询 `SELECT status, completed_at FROM ...`；`SELECT outcome FROM transfer_attempt WHERE transfer_task_id=<id> AND attempt_type='GATEWAY_DELIVER'` | `status=DELIVERED`、`completed_at` 非空、attempt `outcome=SUCCESS`；`task_delivered_total` +1 | `status≠DELIVERED` 或无 attempt |
| AC-31 | 明确失败 4xx/5xx → WAITING_RETRY（T14、BR-41） | Gateway 桩返回 500 | `mvn -Dtest=GatewayFailureIT test`；查询 `SELECT status, retry_count FROM ...` | `status=WAITING_RETRY`、`retry_count` +1；`gateway_failure_total` +1；日志 `event=GATEWAY_SERVER_ERROR` | `status=DELIVERED` 或出现 `FAILED` |
| AC-32 | 超时/连接中断/结果未知 → WAITING_RETRY，`error_code=UNKNOWN_OUTCOME`（T15、BR-41） | Gateway 桩 sleep 超过 `gateway.readTimeout` | `mvn -Dtest=GatewayTimeoutIT test`；查询 `SELECT status, error_code FROM ...`；`SELECT outcome FROM transfer_attempt ...` | `status=WAITING_RETRY`、`error_code=UNKNOWN_OUTCOME`、attempt `outcome=UNKNOWN`；`gateway_timeout_total` +1 | 被判定为成功（`DELIVERED`） |
| AC-33 | 允许重复投递，最终成功（BR-42、EX-54） | 桩第 1 次响应丢失、第 2 次成功 | `mvn -Dtest=GatewayDuplicateDeliveryIT test`；统计桩侧收到的请求次数 | 桩收到 ≥2 次请求；最终 `status=DELIVERED`；`gateway_request_total` ≥2 | 仅调用 1 次且未最终成功 |
| AC-34 | S3 成功不等于完成（BR-37、§76） | S3 桩成功、Gateway 桩失败 | `mvn -Dtest=S3OkGatewayFailIT test`；查询 `SELECT status FROM ...` | `status` 为 `WAITING_RETRY` 或 `GATEWAY_DELIVERING`，**绝不**为 `DELIVERED` | `status=DELIVERED` |
| AC-35 | Gateway 调用在事务外（BR-41、04 §3.4） | 桩内 sleep 5s | 调用期间 `docker exec <pg> psql -U ${PG_USER} -d ${PG_DB} -c "SELECT count(*) FROM pg_stat_activity WHERE state='idle in transaction'"` | 计数为 `0`；调用期间未持有 `transfer_task` 行锁 | 出现长时间 `idle in transaction` |

---

## 7. retry 验收标准

| 编号 | 验收项 | 前置条件/测试数据 | 执行动作（具体命令或操作步骤） | 期望可观察结果 | 不通过判定 |
|---|---|---|---|---|---|
| AC-36 | 指数退避 30s/×2/1h/jitter 20%（BR-43） | 连续失败若干次 | `mvn -Dtest=BackoffCalculatorTest test` | 退避序列为 `30s,60s,120s,...` 且 ≤ `3600s`，含 ±20% 抖动 | 与规格不符或超过 1h |
| AC-37 | 超过 `max_retry_count` 仍继续重试（BR-44、I3） | 将某任务 `retry_count` 置为阈值 +1 | `mvn -Dtest=RetryForeverIT test`；查询 `SELECT status, next_retry_at FROM transfer_task WHERE id=<id>` | `status` 仍为 `WAITING_RETRY`；不存在 `FAILED` 取值；`next_retry_at` 已重新计算 | 出现 `FAILED` 或任务被删除 |
| AC-38 | 重试不占用 worker（BR-46） | 一次执行失败 | 失败后立即 `SELECT worker_id, lease_until FROM transfer_task WHERE id=<id>` | `worker_id` 置空或 lease 已释放；任务回 `WAITING_RETRY`；worker 可 claim 其他任务 | lease 仍活跃，占死 worker |
| AC-39 | WAITING_RETRY 派生下一动作（T17/T18） | 两条任务：A `s3_uploaded_at` 非空，B 为空，均到 `next_retry_at` | `mvn -Dtest=RetryNextActionIT test`；查询两任务目标状态 | A → `GATEWAY_DELIVERING`（T17）；B → `UPLOADING`（T18） | 动作错配（A 重传 S3 或 B 直投） |
| AC-40 | 恢复不早于 `next_retry_at` 且退避有上界（BR-43） | 任务 `next_retry_at` 在未来 | 到期前尝试 claim；查询 `SELECT next_retry_at FROM ...` | 到期前 claim 不命中；`next_retry_at - now() ≤ 1h` | 提前执行或退避超过 1h |

---

## 8. recovery 验收标准

| 编号 | 验收项 | 前置条件/测试数据 | 执行动作（具体命令或操作步骤） | 期望可观察结果 | 不通过判定 |
|---|---|---|---|---|---|
| AC-41 | 启动顺序 DB→Recovery→Retry/Worker→Scanner（基线 §7、§84） | 存在 stale lease 残留任务 | `mvn -Dtest=StartupOrderIT test`；检查启动日志时间序 | 日志中 Recovery 完成时间早于 Scanner 首次扫描时间 | Scanner 先于 Recovery 启动 |
| AC-42 | T10：`UPLOADING` + lease 过期 + `s3_uploaded_at IS NULL` → READY | 手工 `UPDATE transfer_task SET status='UPLOADING', lease_until=now()-interval '10m', s3_uploaded_at=NULL WHERE id=<id>` | 重启 / 触发 Recovery；`mvn -Dtest=RecoveryT10IT test`；查询 `SELECT status FROM ...` | `status=READY`；日志 `RECOVERY_STALE_LEASE ... action=T10->READY`；`recovery_stale_lease_recovered_total{from_status=UPLOADING}` +1 | 仍为 `UPLOADING` 或迁移到其他状态 |
| AC-43 | T12：`S3_UPLOADED` + lease 过期 + `s3_uploaded_at` 非空 → GATEWAY_DELIVERING | 置 `status='S3_UPLOADED'`、`s3_uploaded_at=now()`、lease 过期 | 触发 Recovery；`mvn -Dtest=RecoveryT12IT test` | `status=GATEWAY_DELIVERING`；未重新执行 S3 PUT（`upload_total` 不增长） | 重新进入 `UPLOADING` |
| AC-44 | T16：`GATEWAY_DELIVERING` + lease 过期 → WAITING_RETRY（不猜结果） | 置 `status='GATEWAY_DELIVERING'`、lease 过期 | 触发 Recovery；`mvn -Dtest=RecoveryT16IT test` | `status=WAITING_RETRY`、`error_code=UNKNOWN_OUTCOME`；日志 `action=T16->WAITING_RETRY` | 猜测成功置为 `DELIVERED` |
| AC-45 | 恢复条件更新幂等，影响行数 0 则放弃（I4） | 已恢复过的任务 | 连续执行两次 Recovery；`mvn -Dtest=RecoveryIdempotentIT test` | 第二次影响 0 行，不覆盖仍在运行的 worker；`recovery.conditional_update.skipped` +1 | 抢占活跃 worker 或重复迁移 |
| AC-46 | 优雅关闭 SIGTERM 6 步 + 超时强杀由 lease 兜底（基线 §7、§85） | 运行中且在途有任务 | `kill -TERM <pid>`，观察日志；若超时再 `kill -9 <pid>` 并重启 | 日志顺序：停止扫描→停止 claim→等待在途→释放 lease→退出；强杀后由下次 Recovery 接管，任务不永久卡住 | 强杀导致任务永久停留 `UPLOADING`/`GATEWAY_DELIVERING` |

---

## 9. audit 验收标准

| 编号 | 验收项 | 前置条件/测试数据 | 执行动作（具体命令或操作步骤） | 期望可观察结果 | 不通过判定 |
|---|---|---|---|---|---|
| AC-47 | 每次迁移写 `transfer_event`（S8、BR-65） | 走完一条任务全链路 | 查询 `SELECT event_type, from_status, to_status FROM transfer_event WHERE transfer_task_id=<id> ORDER BY created_at` | 事件条数 = 迁移次数；`from_status`/`to_status` 序列与 T 编号一致 | 缺失任一迁移事件 |
| AC-48 | 每次尝试写 `transfer_attempt`（S9、BR-65） | 至少一次 S3 与一次 Gateway 尝试 | 查询 `SELECT attempt_type, attempt_no, outcome FROM transfer_attempt WHERE transfer_task_id=<id> ORDER BY attempt_no` | 每次尝试 1 行；`attempt_no` 连续且唯一 | 缺失或 `attempt_no` 重复 |
| AC-49 | 非法迁移被拒并审计（04 §3） | 构造非法迁移（如 `READY→S3_UPLOADED`） | `mvn -Dtest=IllegalTransitionAuditIT test`；查询 `transfer_event` | 抛异常；WARN 日志；`transfer_event` 记录 rejected；状态不变 | 无拒绝审计记录或状态被改 |
| AC-50 | supersede 写 `cancel_reason`/`superseded_by_version_no`/event（BR-33、04 §6.3） | 构造旧版本越过阈值 + 新版本 `READY` | `mvn -Dtest=SupersedeAuditIT test`；查询 `SELECT cancel_reason, superseded_by_version_no FROM transfer_task WHERE id=<old>`；`SELECT operator FROM transfer_event WHERE transfer_task_id=<old>` | `cancel_reason=SUPERSEDED_BY_NEWER_VERSION`、`superseded_by_version_no=最新版`、event `operator=system` | 字段缺失或值错误 |
| AC-51 | supersede 不删除任何记录（BR-33、§50） | supersede 前后对比 | supersede 前记录 attempt/event 计数 → 触发 supersede → 再次计数 | attempt/event 计数只增不减；task 行仍在（`status=CANCELLED`） | 出现记录被删除 |

---

## 10. observability 验收标准

| 编号 | 验收项 | 前置条件/测试数据 | 执行动作（具体命令或操作步骤） | 期望可观察结果 | 不通过判定 |
|---|---|---|---|---|---|
| AC-52 | 日志含必需字段（BR-64） | 产生一条迁移日志 | `grep` 一条日志，正则断言同时含 `taskId`、`fileVersionId`、`fileId`、`directoryId`、`customerSpace`、`filePath` | 6 个字段全部存在且非空 | 缺失任一字段 |
| AC-53 | 关键 metric 暴露（11 文档） | 应用运行中 | `curl -s localhost:8080/actuator/prometheus | grep -E "scanner_scan_total|task_backlog_total|recovery_stale_lease_recovered_total"` | 三者均存在且类型正确（counter/gauge） | 缺失任一指标 |
| AC-54 | `task_backlog_total` 只统计 READY+WAITING_RETRY（11 §99） | 构造各状态任务 | 采样 `task_backlog_total` 并与 `SELECT count(*) FROM transfer_task WHERE status IN ('READY','WAITING_RETRY')` 对比 | 两者相等；不含 `UPLOADING`/`S3_UPLOADED`/`GATEWAY_DELIVERING` | 计入了在途状态 |
| AC-55 | `task_oldest_age_seconds` 用 `created_at`（11 §100） | 构造一个较老的 `READY` 任务 | 采样指标并与 `SELECT now()-min(created_at) FROM transfer_task WHERE status IN ('READY','WAITING_RETRY')` 对比 | 两值近似相等 | 用 `updated_at` 导致明显偏小 |
| AC-56 | 应用日志不进 PostgreSQL（BR-57、§55） | 应用运行并产生日志 | `docker exec <pg> psql -U ${PG_USER} -d ${PG_DB} -c "SELECT count(*) FROM information_schema.tables WHERE table_name LIKE '%log%'"`；检查 stdout | 计数为 `0`；日志出现在 stdout | 存在日志表或日志写入 DB |
| AC-57 | 凭据不进日志/DB/Git（BR-58、§56） | 配置了 `${S3_SECRET_KEY}`、`${GATEWAY_APP_KEY}` | `grep -R "${S3_SECRET_KEY}" logs/`；`docker exec <pg> pg_dump ... | grep "${GATEWAY_APP_KEY}"`；`git grep "${S3_SECRET_KEY}"` | 三处均无匹配 | 任一处出现明文凭据 |

---

## 11. manual retry 验收标准

| 编号 | 验收项 | 前置条件/测试数据 | 执行动作（具体命令或操作步骤） | 期望可观察结果 | 不通过判定 |
|---|---|---|---|---|---|
| AC-58 | 最新稳定版本 `WAITING_RETRY→READY`（T19、BR-60） | 任务为最新 `latest_stable` 且 `WAITING_RETRY`、无活跃 lease | 触发 Service 层 `manualRetry(taskId)`（V1 无 HTTP/前端，FR-10）；`mvn -Dtest=ManualRetryIT test` 或运维脚本调用；随后查询状态与 event | 调用成功；`status=READY`；`transfer_event.operator=<操作人>` | 调用失败或状态未变 |
| AC-59 | 非最新版本拒绝（BR-61、T19 守卫） | 已被 supersede 的旧版本 | 对该旧版本调用 `manualRetry(taskId)` | 抛出/返回拒绝错误码 `SUPERSEDED_BY_NEWER_VERSION`；状态不变；写 operator event | 成功复活旧版本 |
| AC-60 | `DELIVERED`/`CANCELLED` 拒绝（04 §6.7、T22/T23） | 终态任务 | 对终态任务调用 `manualRetry(taskId)` | 返回拒绝（终态不可复活）；状态不变 | 终态被复活 |
| AC-61 | 活跃 lease 拒绝（04 §6.7） | 任务处于 `GATEWAY_DELIVERING` 且有活跃 lease | 调用 `manualRetry(taskId)` | 返回拒绝（活跃 lease 冲突）；状态不变；未与执行中 worker 冲突 | 被强制迁移导致冲突 |
| AC-62 | manual retry 留痕（BR-62） | 上述任一成功或拒绝操作 | 查询 `SELECT operator, reason, event_type FROM transfer_event WHERE transfer_task_id=<id> ORDER BY created_at DESC LIMIT 1` | 存在 `operator≠system` 的 event，含 `reason` | 无审计记录 |

---

## 可靠性验收

> 目标：文件不会因 Scanner crash / JVM crash / S3 failure / Gateway failure / network failure 而**永久丢失**。
> 总原则（BR §4）：**文件可以重复，但不能因为中转应用的故障而永久丢失。**

| 编号 | 故障注入方式 | 期望可观察结果（不丢失断言） | 不通过判定 |
|---|---|---|---|
| AC-63 | Scanner crash：扫描进行中 `kill -9 <scanner-thread/pid>`，随后重启应用 | 未落库的文件在下一周期生成 `file_version` + `transfer_task`；已落库文件不重复（I6，AC-02）；最终可投递 | 存在文件永远没有 task（查询该路径 `file_version` 恒为 0） |
| AC-64 | JVM crash during S3：`UPLOADING` 时 `kill -9 <pid>`，重启 | 触发 T10 → `READY` → 重传同一 Object（AC-42）；最终到达 `S3_UPLOADED`/`DELIVERED`；`recovery_stale_lease_recovered_total` 增长 | 任务永久停留 `UPLOADING` 或从 DB 消失 |
| AC-65 | S3 failure：S3 桩返回 500 / 超时 | T9 → `WAITING_RETRY`（AC-27），持续重试直到成功；无删除、无 `FAILED`（BR-44/BR-45） | 任务被删除或停止重试 |
| AC-66 | Gateway failure：桩返回 500 / 401 | T14 → `WAITING_RETRY`（AC-31），永久重试；桩恢复后最终 `DELIVERED` | 任务被删除或出现 `FAILED` 终态 |
| AC-67 | network failure：`netsh advfirewall` / 代理 drop 到 S3 或 Gateway 的连接 | T9 / T15 → `WAITING_RETRY`（`error_code=UNKNOWN_OUTCOME`）；恢复网络后继续；attempt `outcome=UNKNOWN` 有记录 | 任务丢失或被视为成功 |

---

## 一致性验收

> 目标：不出现「同一 file version 多个 task」「两个 worker 同时处理同一 task」「v1 Gateway 读到 v2 S3 object」「retry 后状态错乱」。

| 编号 | 竞态构造方式 | 期望可观察结果（断言） | 不通过判定 |
|---|---|---|---|
| AC-68 | 两线程并发对同一 `file_version` 创建 task | `UNIQUE(file_version_id)`（BR-23、I1）保证恰好 1 行；引用 **T1**；第二线程影响 0 行 | `transfer_task` 出现 2 行 |
| AC-69 | 两 worker 并发 claim 同一 `READY` task | `FOR UPDATE SKIP LOCKED`（BR-48）保证仅一个 claim 成功；引用 **T6**；`TASK_CLAIMED` 仅 1 条 | 两个 worker 都进入 `UPLOADING`/`GATEWAY_DELIVERING` |
| AC-70 | 构造 v1 `GATEWAY_DELIVERING` + 活跃 lease，同时 v2 已 `READY` | v2 无法进入 `UPLOADING`（串行守卫 + 活跃 lease 期间禁止 supersede，BR-28/BR-31）；宽限期 ≥ `gateway.readTimeout + lease.duration + 5m`；引用 **T6 / T20 / T21** 与 04 §3.5 | v2 在 v1 活跃期覆盖同一 S3 Object |
| AC-71 | 反复经历 T9/T14/T15/T16/T17/T18 | 状态序列始终落在合法迁移集合；`s3_uploaded_at` 决定 T17（重投）或 T18（重传）；引用 **T9 / T14 / T15 / T16 / T17 / T18** | 出现非法迁移或下一动作错配 |

---

## 可恢复验收

> 目标：系统重启后未完成任务自动恢复；引用 **T10 / T12 / T16**。

| 编号 | 重启时序 | 期望可观察结果（断言） | 不通过判定 |
|---|---|---|---|
| AC-72 | 注入 `UPLOADING`（`s3_uploaded_at=NULL`、lease 过期）→ `kill -9` → 重启 → Recovery 先于 Scanner | T10：`UPLOADING → READY`（AC-42）；随后被重新 claim 上传 | 任务停留 `UPLOADING` |
| AC-73 | 注入 `S3_UPLOADED`（`s3_uploaded_at` 非空、lease 过期）→ `kill -9` → 重启 | T12：`S3_UPLOADED → GATEWAY_DELIVERING`（AC-43）；不重复 S3 PUT | 重新进入 `UPLOADING` |
| AC-74 | 注入 `GATEWAY_DELIVERING`（lease 过期）→ `kill -9` → 重启 | T16：`GATEWAY_DELIVERING → WAITING_RETRY`（AC-44），`error_code=UNKNOWN_OUTCOME`，退避后重投 | 猜测成功置 `DELIVERED` |
| AC-75 | 存在 stale lease 残留时按顺序启动 | Recovery 完成后 Scanner 才首次扫描（AC-41）；恢复任务先于新任务获得 worker | Scanner 先启动，抢占恢复任务 |

---

## 可追踪验收

> 目标：从 NAS path 可追踪到 `file → file_version → task → attempts → gateway → delivered`（BR-63）。

| 编号 | 具体查询 | 期望输出列 | 不通过判定 |
|---|---|---|---|
| AC-76 | 按 NAS 路径全链查询（示意，非 DDL 交付物）：<br>`SELECT f.relative_path, fv.version_no, fv.status AS fv_status, t.id AS task_id, t.status, t.retry_count, a.attempt_type, a.attempt_no, a.outcome, a.http_status, a.gateway_request_id, t.completed_at FROM file f JOIN file_version fv ON fv.file_id=f.id JOIN transfer_task t ON t.file_version_id=fv.id LEFT JOIN transfer_attempt a ON a.transfer_task_id=t.id WHERE f.relative_path='<nas-path>' ORDER BY fv.version_no, a.attempt_no` | 输出列：`relative_path, version_no, fv_status, task_id, status, retry_count, attempt_type, attempt_no, outcome, http_status, gateway_request_id, completed_at` | 无法从 path 关联到 task/attempt，或关键列缺失 |
| AC-77 | 追踪某任务的全部 attempts：<br>`SELECT attempt_type, attempt_no, outcome, error_code, started_at, finished_at FROM transfer_attempt WHERE transfer_task_id=<id> ORDER BY attempt_no` | 每次 S3/Gateway 尝试均有行，`attempt_no` 连续，含 `outcome` 与时间 | attempt 缺失或序号断裂 |
| AC-78 | 追踪投递结果：<br>`SELECT t.status, t.completed_at, a.http_status, a.gateway_request_id FROM transfer_task t LEFT JOIN transfer_attempt a ON a.transfer_task_id=t.id AND a.attempt_type='GATEWAY_DELIVER' WHERE t.id=<id>` | 成功投递时 `status=DELIVERED` 且存在对应 Gateway attempt 记录（含 `http_status`/`gateway_request_id`） | `DELIVERED` 却无 Gateway attempt 记录 |

---

## 可运维验收

> 目标：运维能直接回答 6 个问题。SQL 均为示意，非 DDL 交付物。

| 编号 | 运维问题 | 具体查询 | 期望可观察结果（断言） | 不通过判定 |
|---|---|---|---|---|
| AC-79 | 当前卡在哪里？ | `SELECT status, count(*) FROM transfer_task GROUP BY status`；`curl -s localhost:8080/actuator/prometheus | grep -E "task_ready|task_waiting_retry|task_uploading|task_delivering"` | 各状态计数与指标一致；可定位堆积状态 | 状态与指标不一致或无法区分 |
| AC-80 | 重试多少次？ | `SELECT max(retry_count), count(*) FROM transfer_task WHERE status='WAITING_RETRY'`；`task_retry_total` | 返回最大重试次数与 WAITING_RETRY 数量；与 `task_retry_total` 口径一致 | 无法得到重试次数 |
| AC-81 | 最近一次错误？ | `SELECT error_code, error_message, finished_at FROM transfer_attempt WHERE transfer_task_id=<id> ORDER BY attempt_no DESC LIMIT 1` | 返回最近一次失败的错误码/信息/时间 | 无错误记录或字段为空 |
| AC-82 | 下次什么时候 retry？ | `SELECT min(next_retry_at) FROM transfer_task WHERE status='WAITING_RETRY'` | 返回最早的重试时间；`≥ now()` 且 `≤ now()+1h`（退避上界） | 无法得到或超过 1h |
| AC-83 | 哪个 worker 在处理？ | `SELECT worker_id, claimed_at, lease_until FROM transfer_task WHERE lease_until > now()` | 列出活跃 worker 及其任务与 lease 到期时间 | 无法定位执行者 |
| AC-84 | 哪些 lease 已过期？ | `SELECT id, status, worker_id, lease_until FROM transfer_task WHERE status IN ('UPLOADING','S3_UPLOADED','GATEWAY_DELIVERING') AND worker_id IS NOT NULL AND lease_until < now()`；`task_stale_lease` | 列出 stale lease 任务；指标 `task_stale_lease` 与查询计数一致 | 无法发现过期 lease 或指标缺失 |

---

## 验收标准 → 业务规则映射

| AC 编号 | BR 编号 | 说明 |
|---|---|---|
| AC-01 | BR-03、BR-07 | 目录 enabled + 后缀过滤 |
| AC-02 | BR-10 | 重复扫描不产生重复 task |
| AC-03 | BR-09 | 流式扫描 |
| AC-04 | BR-12 | 单目录失败不永久停止 |
| AC-05 | BR-11 | 扫描崩溃无 checkpoint |
| AC-06 | BR-08 | 文件逻辑身份唯一 |
| AC-07 | BR-14 | 首次观察 |
| AC-08 | BR-14 | 两次一致判稳 |
| AC-09 | BR-16 | fingerprint 变化重置 |
| AC-10 | BR-15 | fingerprint 定义 |
| AC-11 | BR-51 | 稳定前文件消失 |
| AC-12 | BR-18 | 未稳定不得上传 |
| AC-13 | BR-23、BR-24 | 一个版本一个任务 + DB 唯一约束 |
| AC-14 | BR-25 | 同事务创建 |
| AC-15 | BR-26 | 配置快照固化 |
| AC-16 | BR-27 | 配置只影响新任务 |
| AC-17 | BR-20 | version_no 有序/冗余列 |
| AC-18 | BR-48、BR-49 | 单 worker + 强制 claim 服务 |
| AC-19 | BR-28 | 同路径串行 |
| AC-20 | BR-47 | 并发受限 |
| AC-21 | BR-48 | lease 续租 |
| AC-22 | BR-50 | 有界队列背压 |
| AC-23 | BR-39 | attempt 独立打开文件 |
| AC-24 | BR-35 | Object Key 稳定 |
| AC-25 | BR-36 | 不启用版本控制 |
| AC-26 | BR-37 | S3 成功 ≠ 完成 |
| AC-27 | BR-41 | 失败按失败处理 |
| AC-28 | BR-38 | 流式上传 |
| AC-29 | BR-34 | 覆盖同一 Object |
| AC-30 | BR-37 | 仅 Gateway 成功产生 DELIVERED |
| AC-31 | BR-41 | 明确失败重试 |
| AC-32 | BR-41 | 结果未知按失败 |
| AC-33 | BR-42 | 允许重复投递 |
| AC-34 | BR-37 | S3 成功不等于完成 |
| AC-35 | BR-41 | 外部 IO 在事务外 |
| AC-36 | BR-43 | 指数退避参数 |
| AC-37 | BR-44 | 超过阈值仍重试 |
| AC-38 | BR-46 | 重试不占 worker |
| AC-39 | BR-43 | WAITING_RETRY 派生动作 |
| AC-40 | BR-43 | 退避上界 |
| AC-41 | BR-12 | 启动顺序（可恢复前置） |
| AC-42 | BR-11 | 崩溃恢复重扫/重传 |
| AC-43 | BR-53 | S3 成功后不依赖 NAS |
| AC-44 | BR-41 | 未知结果重投 |
| AC-45 | BR-24 | 条件更新幂等 |
| AC-46 | BR-12 | 优雅关闭与兜底 |
| AC-47 | BR-65 | 迁移事件 |
| AC-48 | BR-65 | attempt 记录 |
| AC-49 | BR-18、BR-60 | 非法迁移拒绝 |
| AC-50 | BR-33 | supersede 审计字段 |
| AC-51 | BR-33、BR-45 | 不删除记录 |
| AC-52 | BR-64 | 日志字段 |
| AC-53 | BR-63 | 关键指标 |
| AC-54 | BR-63 | backlog 口径 |
| AC-55 | BR-63 | 最老任务年龄 |
| AC-56 | BR-57 | 日志不进 DB |
| AC-57 | BR-58 | 凭据不入日志/DB |
| AC-58 | BR-60 | 最新版本可人工重试 |
| AC-59 | BR-61 | 非最新版本拒绝 |
| AC-60 | BR-32 | 终态拒绝 |
| AC-61 | BR-48 | 活跃 lease 拒绝 |
| AC-62 | BR-62 | 人工操作留痕 |
| AC-63 | BR-11、BR-45 | Scanner crash 不丢失 |
| AC-64 | BR-45、I4 | JVM crash during S3 不丢失 |
| AC-65 | BR-44、BR-45 | S3 failure 不丢失 |
| AC-66 | BR-44、BR-45 | Gateway failure 不丢失 |
| AC-67 | BR-41、BR-45 | network failure 不丢失 |
| AC-68 | BR-23 | 同版本单 task |
| AC-69 | BR-48 | 单 worker |
| AC-70 | BR-28、BR-31 | v1/v2 覆盖竞态 |
| AC-71 | BR-43、BR-44 | retry 后状态不错乱 |
| AC-72 | BR-45 | T10 恢复 |
| AC-73 | BR-53 | T12 恢复 |
| AC-74 | BR-41 | T16 恢复 |
| AC-75 | BR-12 | 启动顺序 |
| AC-76 | BR-63 | 全链路追踪 |
| AC-77 | BR-65 | attempt 追踪 |
| AC-78 | BR-63 | 投递结果追踪 |
| AC-79 | BR-63 | 卡在哪里 |
| AC-80 | BR-46 | 重试次数 |
| AC-81 | BR-65 | 最近错误 |
| AC-82 | BR-43 | 下次 retry 时间 |
| AC-83 | BR-48 | 哪个 worker |
| AC-84 | BR-48 | 过期 lease |

---

## 端到端验收场景（E2E）

所有 E2E 均在「验收前置条件与环境」就绪后执行；S3 与 Gateway 使用桩，NAS 用本地临时目录模拟。

### E2E-1 正常投递（NAS → S3 → Gateway → DELIVERED）

| 步骤 | 操作 | 每步断言 |
|---|---|---|
| 1 | 在 NAS 临时目录写入 `demo/e2e-1.csv`（size/mtime 固定） | 文件存在且 `size`、`mtime` 稳定 |
| 2 | 触发第一次扫描 | `file_version` 生成；task `status=DISCOVERED`（T1） |
| 3 | 等待稳定判定 | 相邻两次观察一致；task `status=READY`、`stable_at` 非空（T4） |
| 4 | 等待 worker claim 并上传 | 短暂进入 `UPLOADING` → `S3_UPLOADED`；`s3_uploaded_at` 非空（T8）；S3 桩存在 Key=`<customer-space>/demo/e2e-1.csv` 对象 |
| 5 | 等待 Gateway 投递 | `GATEWAY_DELIVERING` → `DELIVERED`（T13）；`completed_at` 非空；`transfer_attempt(GATEWAY_DELIVER, outcome=SUCCESS)` |
| 6 | 核对指标与追踪 | `task_delivered_total` +1；AC-76 查询返回该 path 的完整链路，`status=DELIVERED` |

**不通过判定**：任一步状态与期望不符，或最终未到 `DELIVERED`。

### E2E-2 Gateway 失败后恢复投递

| 步骤 | 操作 | 每步断言 |
|---|---|---|
| 1 | 前置同 E2E-1 步骤 1–4，使任务到达 `S3_UPLOADED` | `s3_uploaded_at` 非空，S3 对象存在 |
| 2 | 配置 Gateway 桩返回 500，触发投递 | task → `WAITING_RETRY`（T14）；`retry_count=1`；`gateway_failure_total` +1 |
| 3 | 将 Gateway 桩切换为成功契约 | 桩就绪 |
| 4 | 等待 `next_retry_at` 到期（可临时缩短退避或改时间） | 到期后 task → `GATEWAY_DELIVERING`（T17，因 `s3_uploaded_at` 非空，不重传 S3） |
| 5 | 等待投递完成 | task → `DELIVERED`（T13）；`completed_at` 非空 |
| 6 | 核对无 `FAILED`、无删除 | 任务仍存在；状态历史无 `FAILED`；attempt 记录 ≥2 条 |

**不通过判定**：失败后任务被删除、出现 `FAILED`、或恢复后仍无法 `DELIVERED`。

### E2E-3 同路径 v1/v2 supersede 后投递最新版

| 步骤 | 操作 | 每步断言 |
|---|---|---|
| 1 | 写入 `demo/e2e-3.csv` v1 并使其稳定 `READY` | v1 task `status=READY`、`version_no=1` |
| 2 | 让 v1 持续 Gateway 失败，累积 `retry_count` 越过 `supersede.afterFailedAttempts`（或等待超过 `afterWaiting`） | v1 处于 `WAITING_RETRY`，`retry_count ≥ 5` |
| 3 | 修改 NAS 文件内容与 `mtime`，触发新版本 | 生成 v2 `file_version`，`version_no=2`；v1 与 v2 同 `file_id` |
| 4 | 等待 v2 稳定 | v2 `status=READY`；因串行守卫，v2 此时不可进入 `UPLOADING`（AC-19） |
| 5 | 触发 `SupersedeService`（周期执行或 claim 前校验） | v1 lease 不活跃、宽限期已过 → v1 → `CANCELLED`（T20/T21）；`cancel_reason=SUPERSEDED_BY_NEWER_VERSION`、`superseded_by_version_no=2` |
| 6 | 等待 v2 执行 | v2 被 claim → `UPLOADING` → `S3_UPLOADED` → `GATEWAY_DELIVERING` → `DELIVERED` |
| 7 | 核对最终一致性 | S3 对象为 v2 内容；v1 `status=CANCELLED` 且 `DELIVERED` 记录不存在；客户侧最终收到 v2 |

**不通过判定**：v2 被 v1 永久阻塞、v1 未被 supersede、或最终投递的是 v1 内容。

---

## 验收前置条件与环境

| 项 | 要求 |
|---|---|
| JDK | **JDK 21**（`java -version` 显示 21；编译与运行均用 21） |
| 构建 | **Maven**（`mvn -v` 可用；测试命令形如 `mvn -Dtest=<IT> test`） |
| 数据库 | **复用 Docker 中已运行的 PostgreSQL（localhost:5432）**；库名/用户/密码由用户提供，文中一律用 `${PG_USER}`/`${PG_DB}`/`${PG_PASSWORD}` 占位；集成测试**可选** Testcontainers（若使用则每次拉起独立实例） |
| S3 | **S3 桩 / Mock**（如 LocalStack 或自定义 Mock endpoint），支持 200/500/超时/覆盖写；桶名 `${S3_BUCKET}`，凭据 `${S3_ACCESS_KEY}`/`${S3_SECRET_KEY}` |
| Gateway | **Gateway 桩**，可切换「明确成功 / 4xx / 5xx / 超时 / 响应丢失 / 429」；凭据 `${GATEWAY_APP_KEY}` |
| NAS | **本地临时目录模拟**（如 `${env:PI_SCRATCH_DIR}\nas-mock\`），支持写入、追加、删除、权限调整 |
| 状态集合 | 恰好 9 个（基线 §1）；测试断言中**不得**出现 `FAILED`/`CLAIMED`/`PROCESSING`/`SUPERSEDED` |
| 关键默认值 | `lease.duration=5m`、`supersede.gracePeriod=15m`、`supersede.afterFailedAttempts=5`、`supersede.afterWaiting=30m`、退避 `30s/×2/1h/jitter20%`、并发 `8/8/2–4` |
| 凭据 | 全部占位符，不得写入仓库或日志（BR-58、BR-59） |

---

## 验收不通过的处理

1. **一票否决**：任一 AC 不通过，**不得进入下一 Phase**；必须定位根因并修复后**重跑该 AC 及其关联 AC**。
2. **重跑范围**：单条 AC 失败时，至少重跑本模块全部 AC + 对应的 E2E 场景；涉及状态机或一致性的失败，必须重跑一致性验收与全部 E2E。
3. **回归要求**：修复后需重跑 `可靠性验收`、`一致性验收`、`可恢复验收` 中受影响的 AC，避免修复引入新缺陷。
4. **不可放宽**：涉及「文件不丢失」「终态不可复活」「无 `FAILED`」「9 状态集合」的 AC 为**硬门槛**，任何情况下不得以「业务可接受」为由豁免。
5. **留痕**：失败与修复过程须记录失败的 AC 编号、复现步骤、根因与修复提交，作为 Phase 0 验收结论附件。

---

## 【架构问题】

### 【架构问题】AC-1：Gateway「明确成功」契约未定义，导致 T13 相关 AC 不可客观判定

- **问题**：`Q3`（Gateway 认证方式、成功响应契约、幂等键）仍为开放问题。AC-30/AC-33 要求断言「Gateway 明确返回成功 → `DELIVERED`」，但「明确成功」的响应契约尚未冻结。
- **风险**：AC-30、AC-33 无法写成与真实 Gateway 一致的断言；幂等键缺失会使「重复投递」的验收只能验证次数、无法验证客户侧去重。
- **建议方案**：Phase 1 开始前确认 Gateway 成功响应契约（HTTP 状态码 + 响应体字段）与幂等键；在契约冻结前，AC-30/AC-33 以桩契约「HTTP 2xx + `status=SUCCESS`」作为临时断言口径并显式标注。
- **对现有设计的影响**：仅影响 Gateway 相关 AC 的断言口径；状态机与数据模型不变。

### 【架构问题】AC-2：恢复/可观测指标命名在 `09-crash-recovery-model.md` 与 `11-observability-model.md` 中不一致

- **问题**：`09` 使用 `recovery.stale_lease.candidates`、`recovery.transition.T10.count`、`recovery.T16.count`；`11` 使用 `recovery_stale_lease_recovered_total`（带 `from_status`/`to_status` 标签）。同一语义存在两套名称。
- **风险**：验收脚本、告警规则、AC-42/AC-43/AC-44 的断言依据不唯一，可能出现「实现按 09、断言按 11」的假失败。
- **建议方案**：统一以 `11-observability-model.md` 的命名为准，回改 `09` 中的指标名；本文件 AC 断言统一采用 `recovery_stale_lease_recovered_total{from_status,to_status}`。
- **对现有设计的影响**：仅文档命名对齐；指标语义与状态机不变。

### 【架构问题】AC-3：v1 Gateway 读到 v2 S3 对象无法绝对消除（沿用 A1）

- **问题**：S3 Object Key 不含版本，`v1 GATEWAY_DELIVERING` 与 `v2 UPLOADING` 的覆盖竞态只能靠「活跃 lease 期间禁止 supersede + 宽限期 ≥ readTimeout + lease.duration + 5m」收敛（04 §3.5、A1），无法从设计上绝对消除。
- **风险**：AC-70 只能断言「在 v1 活跃期 v2 不进入 `UPLOADING`、宽限期约束成立」，**不能**断言「客户绝不读到错误版本」；若把 AC-70 写成绝对断言，会与已确认架构冲突并导致永远不通过。
- **建议方案**：接受残余风险（At-least-once 固有代价，业务已接受）；AC-70 按「收敛而非绝对」口径验收；若需更强保证，需 Gateway 契约支持对象标识（ETag/size）校验（依赖 AC-1 的契约确认）。
- **对现有设计的影响**：不改变架构；仅明确一致性验收的口径边界。
