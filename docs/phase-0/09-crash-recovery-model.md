# 09 Crash Recovery Model（崩溃恢复模型）

## 本部分范围

本文件描述「NAS → S3 → B2B Gateway → 客户 SFTP 中转系统」在 JVM 崩溃、进程被强杀（`kill -9`）、优雅关闭超时以及 Gateway 长期失败等异常下的**恢复语义与恢复算法**，具体包括：

- 4 个崩溃点的逐点拆解：崩溃时刻、DB 残留状态、外部系统真实状态、恢复判定条件、恢复动作（含迁移编号）、幂等性论证、可观测信号。
- 强制启动恢复顺序，以及「Scanner 必须最后启动」的原因。
- Stale Lease 恢复算法（条件更新语义，覆盖 `UPLOADING` / `S3_UPLOADED` / `GATEWAY_DELIVERING` 三种残留状态）。
- 优雅关闭（SIGTERM）的 6 步流程与超时强杀兜底。
- 崩溃场景下系统不变量 I3 / I4 / I5 的保持方式。
- 恢复正确性论证（At-least-once + 条件更新 + lease）与最坏恢复时间上界。
- 崩溃恢复测试矩阵（≥ 12 行）。

本文件的状态集合、迁移编号、supersede 规则与《Phase 0 决策基线》§1–§3 **逐字一致**；冲突时以基线为准。

### 状态集合（恰好 9 个，本文档不新增任何状态）

| # | 状态 | 是否终态 | 与崩溃恢复的关系 |
|---|---|---|---|
| 1 | `DISCOVERED` | 否 | 不持有 lease，崩溃不影响 |
| 2 | `STABILITY_CHECK` | 否 | 不持有 lease，崩溃后重扫即可 |
| 3 | `READY` | 否 | 崩溃恢复的目标状态之一（T10） |
| 4 | `UPLOADING` | 否 | 崩溃点 1 / 2 的残留状态 |
| 5 | `S3_UPLOADED` | 否 | 崩溃残留状态之一，恢复目标 `GATEWAY_DELIVERING`（T12） |
| 6 | `GATEWAY_DELIVERING` | 否 | 崩溃点 3 的残留状态，恢复目标 `WAITING_RETRY`（T16） |
| 7 | `WAITING_RETRY` | 否 | 崩溃点 4 的长期状态，由 T17 / T18 再执行 |
| 8 | `DELIVERED` | **是** | 终态，恢复流程不得触碰（T22 禁止） |
| 9 | `CANCELLED` | **是** | 终态，恢复流程不得触碰（T23 禁止） |

> 明确禁止：本文档不引入 `FAILED`、`CLAIMED`、`PROCESSING`、`SUPERSEDED` 等任何额外状态。失败任务**永不删除、永不置为失败终态**，只能停留在 `WAITING_RETRY` 并持续重试（对应迁移 T14 / T15 / T16，不变量 I3）。

## 本部分不做什么

- 不写业务代码，不产出 `*.java`、`*.sql`、`pom.xml`、`mvnw`；本文 SQL 均为「示意，非 DDL 交付物」，仅用于精确表达语义。
- 不定义表结构 DDL、索引 DDL、列类型与约束的最终形态（由数据模型文档负责）。
- 不定义 S3 / Gateway / SFTP 的对外接口契约（由外部集成文档负责）。
- 不定义监控系统的选型与告警阈值（只列恢复相关可观测信号）。
- 不设计多实例部署下的恢复互斥（V1 为单机部署；多实例仅留扩展点，见基线 A8）。
- 不修改已确认架构；发现冲突只标注 `【架构问题】`。
- 不讨论 `DISCOVERED` / `STABILITY_CHECK` 阶段的扫描细节（Scanner 崩溃只需保证「重复扫描不产生重复 task」，由唯一约束保证）。

## 对应整体设计文档章节

| 整体设计章节 | 内容 | 本文件对应位置 |
|---|---|---|
| §16 Lease | `lease_until`、续租、stale task | 全篇；「Stale Lease 恢复算法」 |
| §17 Crash Recovery | `UPLOADING` → `READY` 恢复流程 | 崩溃点 1 / 2 |
| §18 为什么允许重复执行 | S3 覆盖写、不产生多版本 | 崩溃点 1 / 2 的幂等性论证 |
| §48 Gateway Worker | 成功事务写 `DELIVERED` | 崩溃点 3 / 4 |
| §49 Gateway 失败 | `WAITING_RETRY`、`retry_count`、`next_retry_at` | 崩溃点 3 / 4 |
| §50 不删除失败任务 | 无限重试，永不删除 | 崩溃点 4；恢复不变量 I3 |
| §64 JVM Crash 流程 | Crash during S3 / Crash during Gateway | 崩溃点 1–3 |
| §65 Scanner Crash | 重扫 + 唯一约束 | 启动恢复顺序 |
| §66 数据库事务原则 | 状态更新尽量原子 | Stale Lease 算法、幂等性论证 |
| §67 为什么不需要分布式事务 | Durable State + Retry + At-least-once | 恢复正确性论证 |
| §68 Exactly-once 的处理 | 结果未知一律重试 | 崩溃点 3；恢复不变量 I5 |
| §84 系统启动恢复 | 启动顺序、Scanner 最后启动 | 启动恢复顺序 |
| §85 Graceful Shutdown | SIGTERM 6 步 | 优雅关闭（SIGTERM） |
| §92 V1 验收标准 | `kill -9` 场景、Gateway 永久不可用 | 崩溃恢复测试矩阵 |

---

## 崩溃点 1：S3 上传过程中 JVM 崩溃

### 崩溃时刻

Worker 已 claim 任务并进入 `UPLOADING`，正在从 NAS 流式读取文件并 `PUT` 到 S3；此刻进程被 `kill -9` 或被 OOM Killer 终止，或在 `PUT` 进行中操作系统强制重启。S3 端请求可能尚未完成、可能部分写入、也可能已经成功但客户端未知。

### 崩溃后 DB 残留状态

| 字段 | 残留值 |
|---|---|
| `transfer_task.status` | `UPLOADING` |
| `worker_id` / `claimed_at` | 仍为崩溃前 worker（非 NULL） |
| `lease_until` | 崩溃前写入的值，随真实时间推移逐渐过期 |
| `s3_uploaded_at` | `NULL`（PUT 结果未提交） |
| `retry_count` | 未增加 |
| `transfer_attempt` | 可能只有 `attempt_no = N`、`finished_at` 为 NULL、`outcome` 尚未落定的进行中记录（无 `SUCCESS`/`FAILURE` 结论） |

任务**没有被删除**，也没有任何 `FAILED` 记录——DB 是唯一事实源，残留状态完整可判定。

### 外部系统真实状态

- S3：不确定。可能是「对象不存在」「对象存在但内容截断」「对象完整存在」三者之一。
- 客户 SFTP：未受影响（Gateway 尚未被调用）。
- NAS：原文件仍在（本系统只读 NAS）。

### 恢复判定条件

1. `status = 'UPLOADING'`；
2. `worker_id IS NOT NULL AND lease_until < now()`（有主且 lease 已过期，说明原 worker 已死亡或失联）；
3. `s3_uploaded_at IS NULL`（DB 中没有 S3 成功的记录）。

三条同时满足 → 判定为崩溃点 1 / 2 的残留任务，恢复目标为 `READY`。

### 恢复动作（含迁移编号）

```
UPLOADING --(T10, 条件更新)--> READY
READY --(T6, 重新 claim)--> UPLOADING --(T8)--> S3_UPLOADED
```

- T10：`UPLOADING` → `READY`，触发条件「崩溃恢复：lease 过期且 `s3_uploaded_at IS NULL`」，守卫要求条件更新，避免覆盖仍在执行的 worker。
- 恢复时一并清空 `worker_id` / `claimed_at` / `lease_until`，使任务重新进入可 claim 池。
- 重新 claim 后**重新从 NAS 读取并 PUT 到同一个 S3 Object Key**（`<customer-space-code>/<directory-relative-path>/<file-relative-path>`），允许覆盖。
- 不新增 task、不新增 `file_version`（`UNIQUE(file_version_id)` 保证一个版本只有一个任务）。

### 幂等性论证

- S3 的 `PUT` 对同一 Object Key 是**覆盖语义**，且基线明确不启用 S3 对象版本控制，因此重复 `PUT` 不会产生多版本，最终对象恒为「NAS 上该版本的内容」。
- 恢复动作是**条件更新**：`WHERE id=? AND status='UPLOADING' AND worker_id IS NOT NULL AND lease_until < now()`。影响行数为 1 表示本次恢复生效；影响行数为 0 表示任务已被其他恢复者接管或状态已变化，**直接放弃本次动作**，不报错、不重试本条。
- 若崩溃前 PUT 实际已成功，重新 PUT 只是把同一内容再写一次，外部可观察结果不变（At-least-once 的固有代价，业务已接受）。
- 重复执行 Recovery 是安全的：第二次执行时任务已不在 `UPLOADING`，条件更新影响行数为 0。

### 可观测信号

- 指标：`recovery.stale_lease.candidates`、`recovery.transition.T10.count`、`recovery.conditional_update.skipped`（影响行数 0 的次数）。
- 日志：`WARN RECOVERY_STALE_LEASE taskId=... status=UPLOADING leaseUntil=... action=T10->READY`。
- 审计：`transfer_event` 记录状态迁移（`operator = system`）；`transfer_attempt` 保留原失败/中断尝试。
- DB 观测：`SELECT status, count(*) FROM transfer_task WHERE status='UPLOADING' AND lease_until < now() GROUP BY status;` 应在一个调度周期内归零。

---

## 崩溃点 2：S3 上传成功但 DB 状态未提交即崩溃

### 崩溃时刻

S3 `PUT` 已返回 200，S3 端对象**已真实存在且完整**；但在执行 `UPDATE transfer_task SET status='S3_UPLOADED', s3_uploaded_at=now() ...` 并 `COMMIT` 之前（或提交结果未知时），JVM 被 `kill -9`。这是崩溃点 1 的一个特殊子情形：**外部世界已成功，DB 不知道**。

### 崩溃后 DB 残留状态

与崩溃点 1 **完全一致**：

| 字段 | 残留值 |
|---|---|
| `status` | `UPLOADING` |
| `s3_uploaded_at` | `NULL` |
| `lease_until` | 崩溃前值，随后过期 |
| `transfer_attempt` | 可能无成功记录 |

关键点：DB 无法区分「PUT 没发生」与「PUT 成功了但状态没提交」——因此**不猜测**，一律按未成功处理。

### 外部系统真实状态

- S3：对象**已存在且内容正确**。
- 客户 SFTP：未受影响。
- NAS：原文件仍在。

### 恢复判定条件

与崩溃点 1 使用**同一条判定**：`status='UPLOADING' AND worker_id IS NOT NULL AND lease_until < now() AND s3_uploaded_at IS NULL`。

不引入任何「先探测 S3 是否存在」的额外判定作为恢复前提——探测只能作为**观测信号**，不能作为分支依据（否则又变成「猜测上次是否成功」）。

### 恢复动作（含迁移编号）

```
UPLOADING --(T10, 条件更新)--> READY --(T6)--> UPLOADING --(T8)--> S3_UPLOADED
```

- 与崩溃点 1 相同的 T10 → T6 → T8 路径。
- **重新上传并覆盖同一 Object**：S3 中已存在的对象被同内容覆盖，结果不变。
- 不因「S3 可能已存在」而跳过上传：跳过会在「对象实际截断/损坏」时造成永久错误，违反「不允许丢失」。

### 幂等性论证

- 覆盖写幂等：同一 Object Key 的重复 `PUT` 收敛到同一最终内容；不启用版本控制，因此不存在对象版本堆积。
- 条件更新保证同一残留任务在同一时刻只被一个恢复者推进（影响行数 1 vs 0）。
- 崩溃点 1 与崩溃点 2 在恢复算法上**完全合并**，说明算法不依赖对 S3 真实状态的判断，从而不会因判断错误而丢失文件。
- 残余代价仅为一次冗余的 S3 传输（5GB/天规模下可接受），换得的是「不丢文件」。

### 可观测信号

- 指标：`recovery.T10.count` 与 `storage.put.overwrite.count`（同一 Object Key 被覆盖写次数）；理想情况下与崩溃次数同量级，不应持续增长。
- 日志：`WARN RECOVERY_T10_REUPLOAD taskId=... objectKey=... reason=S3_MAYBE_SUCCEEDED`。
- DB 观测：同一 `transfer_task` 的 `transfer_attempt` 中出现两条 `UPLOAD` 类型 attempt（第一条中断、第二条成功），属预期。
- 校验信号（可选，见开放问题 Q6）：恢复后比对 S3 对象 size 与 NAS 文件 size，仅作告警，不作恢复分支。

---

## 崩溃点 3：Gateway 请求已发出、JVM 在收到响应前崩溃

### 崩溃时刻

Worker 已 claim 任务并进入 `GATEWAY_DELIVERING`，HTTP 请求已发出（可能已被 Gateway 接收并投递到客户 SFTP，也可能尚在网关侧处理），JVM 在收到响应或解析响应之前被 `kill -9`。DB 中没有本次调用的结果记录。

### 崩溃后 DB 残留状态

| 字段 | 残留值 |
|---|---|
| `status` | `GATEWAY_DELIVERING` |
| `s3_uploaded_at` | 非 NULL（S3 阶段已完成） |
| `worker_id` / `claimed_at` / `lease_until` | 崩溃前 worker 的 lease，随后过期 |
| `retry_count` | 未增加 |
| `transfer_attempt` | 可能只有一条 `finished_at` 为 NULL、`outcome` 尚未落定的 Gateway attempt，无 `SUCCESS` / `FAILURE` 结论 |

### 外部系统真实状态

- S3：对象存在且为当前版本（S3 阶段已完成）。
- B2B Gateway / 客户 SFTP：**不确定**。三种可能：请求未到达；请求到达但投递失败；请求到达且投递成功（客户 SFTP 已有文件）。
- 系统无法从任何本地信息判断属于哪一种——这正是 §68 描述的「success + response lost」场景。

### 恢复判定条件

1. `status = 'GATEWAY_DELIVERING'`；
2. `worker_id IS NOT NULL AND lease_until < now()`（有主且 lease 已过期）；
3. （防御性）`s3_uploaded_at IS NOT NULL`；若为 NULL，记录数据异常告警，但恢复目标仍按 `GATEWAY_DELIVERING` 处理，不得回退到 `UPLOADING`（`GATEWAY_DELIVERING → UPLOADING` 是非法迁移）。

### 恢复动作（含迁移编号）

```
GATEWAY_DELIVERING --(T16, 条件更新)--> WAITING_RETRY
WAITING_RETRY --(T17, next_retry_at <= now())--> GATEWAY_DELIVERING --(T13/T14/T15)--> DELIVERED / WAITING_RETRY
```

- T16：`GATEWAY_DELIVERING` → `WAITING_RETRY`，触发条件「崩溃恢复：lease 过期」，失败处理为「不猜测上次是否成功，直接重投」。
- 恢复时写 `error_code = UNKNOWN_OUTCOME`（与 T15 语义一致），`retry_count + 1`，`next_retry_at = backoff(...)`。
- 清空 `worker_id` / `claimed_at` / `lease_until`，使任务可被再次 claim。
- 到 `next_retry_at` 后由 T17 重新进入 `GATEWAY_DELIVERING` 并重新调用 Gateway。

### 幂等性论证

- **允许重复投递**是基线明确的一致性语义（At-least-once，不变量 I5）；最坏结果是客户 SFTP 收到同一文件两次，业务已接受。
- 重投使用**当前 S3 对象**（S3 Object Key 不含版本号），因此即使期间发生了新版本覆盖，客户最终拿到的是「最终版本内容」，最终一致性成立（基线 §3.5）。
- 条件更新（`WHERE id=? AND status='GATEWAY_DELIVERING' AND worker_id IS NOT NULL AND lease_until < now()`）保证只有一个恢复者推进该任务；影响行数 0 → 放弃。
- 恢复动作不创建新 task、不创建新 `file_version`，重复执行收敛到同一状态。
- 与 supersede 的交互：`GATEWAY_DELIVERING` 且 lease 活跃时**禁止** supersede；只有 lease 过期并经 T16 转为 `WAITING_RETRY` 后，才参与下一轮 supersede 评估（基线 §3.3 第 4 步）。

### 可观测信号

- 指标：`recovery.T16.count`、`gateway.attempt.outcome.unknown.count`、`gateway.duplicate_delivery.suspected`（同一 task 在客户侧可观测的重复投递）。
- 日志：`WARN RECOVERY_STALE_LEASE taskId=... status=GATEWAY_DELIVERING action=T16->WAITING_RETRY errorCode=UNKNOWN_OUTCOME`。
- 审计：`transfer_attempt` 保留「结果未知」的 attempt 记录（`outcome = UNKNOWN`，见基线 A4），与后续成功 attempt 并存。
- DB 观测：`status='GATEWAY_DELIVERING' AND lease_until < now()` 的计数应在一个调度周期内归零。

---

## 崩溃点 4：Gateway 长期失败

### 崩溃时刻

本崩溃点**不涉及 JVM 崩溃**：进程持续正常运行，但 B2B Gateway 持续不可用或持续失败（连接拒绝、5xx、认证失败、超时、客户 SFTP 不可达等）。任务在 `GATEWAY_DELIVERING` → `WAITING_RETRY` 之间反复循环，失败次数不断累积。这里的「崩溃」是**外部依赖的长期崩溃**。

### 崩溃后 DB 残留状态

| 字段 | 残留值 |
|---|---|
| `status` | `WAITING_RETRY` |
| `retry_count` | 单调递增（每次 T14 / T15 递增 1） |
| `next_retry_at` | 由指数退避计算，随时间向后推进 |
| `error_code` / `error_message` / `http_status` / `gateway_request_id` | 每次失败写入对应的 attempt |
| `lease` | 不活跃（无活跃 worker 持有） |

任务始终存在，**没有任何路径会把它删除或置为失败终态**。

### 外部系统真实状态

- Gateway：持续失败/不可达。
- 客户 SFTP：无该文件。
- S3：对象已存在（`s3_uploaded_at IS NOT NULL`），且可能被更新版本覆盖。
- 系统无法确定 Gateway 何时恢复，因此**不设终止条件**。

### 恢复判定条件

- `status = 'WAITING_RETRY'`；
- `next_retry_at IS NULL OR next_retry_at <= now()`；
- `s3_uploaded_at IS NOT NULL`（决定走 T17 而非 T18）；
- 串行守卫通过（同 `file_id` 下无更早的非终态版本）。

### 恢复动作（含迁移编号）

```
WAITING_RETRY --(T17)--> GATEWAY_DELIVERING --(T14/T15)--> WAITING_RETRY --(T17)--> ...
```

- T17：`WAITING_RETRY` → `GATEWAY_DELIVERING`，触发条件「`next_retry_at <= now()` 且 `s3_uploaded_at IS NOT NULL`」。
- 退避策略：指数退避（`retry_count` 增大则间隔增大），并设一个**间隔上限**以避免无限拉长；间隔上限只影响节奏，**不影响「永不停止」**。
- `max_retry_count` 的语义：**不是终止条件，也不是失败终态**，而是「进入长期重试模式（长退避、纳入 supersede 的 `afterFailedAttempts` 判定、可观测告警升级）」的阈值。超过该阈值后任务仍停留在 `WAITING_RETRY` 并继续按 T17 重试，直到 Gateway 成功（T13 → `DELIVERED`）。
- 若此时 `s3_uploaded_at IS NULL`（例如 S3 长期不可用），则走 T18：`WAITING_RETRY` → `UPLOADING` 重新上传，同样无限重试。
- 唯一的「退出」路径是：`DELIVERED`（成功）、`CANCELLED`（被新稳定版本 supersede，T20 / T21）——**不存在因失败次数过多而终止的路径**。

### 幂等性论证

- 每次 T17 都重新执行 Gateway 调用；Gateway 侧结果未知时按 T15 记 `UNKNOWN_OUTCOME` 并允许重复投递（I5）。
- `retry_count` 与 `next_retry_at` 的更新在同一事务内完成，重启后从 DB 读取，不会因为进程重启而重置退避进度，也不会重复扣减。
- 重复执行 Dispatcher（多个调度周期命中同一任务）由条件更新与 `FOR UPDATE SKIP LOCKED` claim 保证同一任务同一时刻只被一个 worker 处理。
- 长期重试下任务不删除，满足 I3；`WAITING_RETRY` 非终态，因此永不进入 1 年保留清理（I7 / 基线 §8）。
- 与 supersede 的交互：`retry_count` 单调增长，必然在有限时间内越过 `supersede.afterFailedAttempts`（或 `afterWaiting`），因此旧版本**一定**会被 supersede 取消，新版本不会被永久阻塞（基线 §3.4）。

### 可观测信号

- 指标：`task.waiting_retry.count`、`task.retry_count.max`、`task.oldest_waiting_retry.age`、`gateway.failure.rate`。
- 告警：`retry_count > max_retry_count` 时升级告警级别（说明外部依赖长期不可用），但**不得**触发任何终止/删除动作。
- 日志：`WARN GATEWAY_RETRY taskId=... retryCount=... nextRetryAt=... errorCode=...`。
- DB 观测：`SELECT count(*) FROM transfer_task WHERE status='WAITING_RETRY'` 只增不减属预期，除非 Gateway 恢复。

---

## 启动恢复顺序

启动阶段必须遵循**强制顺序**，不得并行启动：

```
1. DB 连接（DataSource 就绪 + 连接可用性验证）
2. 配置校验（lease.duration、退避参数、并发配额、目录配置、必填凭据占位符）
3. Recovery（stale lease 恢复：UPLOADING / S3_UPLOADED / GATEWAY_DELIVERING）
4. Retry/Dispatcher + Worker（重试调度器与 worker 池，claim 通道打开）
5. Scanner（定时扫描 NAS，产生新任务）
```

```text
DB 连接
   ↓
配置校验
   ↓
Recovery（stale lease）
   ↓
Retry/Dispatcher + Worker
   ↓
Scanner（最后启动）
```

### 为什么 Scanner 必须最后启动

- 如果 Scanner 先启动，它会在恢复尚未完成时批量产生 `READY` 任务；这些**新任务**与 Recovery 刚释放出来的**恢复任务**会在同一时刻争抢同一个有界 worker 池与并发配额。
- 后果一：恢复任务被新任务挤后，崩溃前的在途任务被长时间延迟，客户侧交付延迟放大。
- 后果二：worker 池被占满后 claim 停止，Scanner 产生的大量 `READY` 任务堆积在 DB backlog，队列背压行为难以观测，故障定位困难。
- 后果三：`Recovery` 与 `Scanner` 同时写库，会放大 `transfer_task` 上的行锁竞争与 `SKIP LOCKED` 抖动。
- 先完成 Recovery 再启动 Scanner，可以保证「先清空崩溃遗留，再引入新工作」，worker 池在稳定状态下开始接收新任务。
- 前置的 DB 连接必须先于 Recovery（否则无从读取 stale task）；配置校验必须先于 Recovery（否则 lease 时长与退避参数未定，恢复判定可能用错阈值）。

> 启动过程中任一阶段失败：不进入下一阶段，进程以非零退出码终止（避免「半启动」状态下 Scanner 独自运行）。DB 暂时不可用时的行为见基线「故障场景矩阵」：等待/失败恢复，不静默跳过 Recovery。

## Stale Lease 恢复算法

### 算法总览

```
每个恢复周期：
  1. 只读扫描候选：status ∈ {UPLOADING, S3_UPLOADED, GATEWAY_DELIVERING} 且**有主**（worker_id 非空）且 lease 过期
  2. 对每个候选，按 (status, s3_uploaded_at) 决定恢复目标
  3. 逐条执行「条件更新」，检查影响行数
  4. 影响行数 = 1 → 本次恢复生效，写审计
     影响行数 = 0 → 任务已被别的 worker/恢复者接管，放弃本次动作（不重试本条）
  5. 记录指标与日志
```

### 示意 SQL（示意，非 DDL 交付物）

步骤 1：候选扫描（只读，用于观测与分批）。

```sql
-- 示意，非 DDL 交付物
SELECT id, status, s3_uploaded_at, worker_id, lease_until
FROM transfer_task
WHERE status IN ('UPLOADING', 'S3_UPLOADED', 'GATEWAY_DELIVERING')
  AND worker_id IS NOT NULL AND lease_until < now()      -- 仅处理「有主」且 lease 过期的任务
ORDER BY lease_until ASC
LIMIT :batchSize;
```

步骤 2a：`UPLOADING` 且 `s3_uploaded_at IS NULL` → `READY`（迁移 T10）。

```sql
-- 示意，非 DDL 交付物
UPDATE transfer_task
SET status         = 'READY',
    worker_id      = NULL,
    claimed_at     = NULL,
    lease_until    = NULL,
    updated_at     = now()
WHERE id = :taskId
  AND status = 'UPLOADING'
  AND worker_id IS NOT NULL AND lease_until < now()
  AND s3_uploaded_at IS NULL;
```

步骤 2b：`S3_UPLOADED` 且 `s3_uploaded_at IS NOT NULL` → `GATEWAY_DELIVERING`（迁移 T12）。

```sql
-- 示意，非 DDL 交付物
UPDATE transfer_task
SET status         = 'GATEWAY_DELIVERING',
    worker_id      = NULL,
    claimed_at     = NULL,
    lease_until    = NULL,
    updated_at     = now()
WHERE id = :taskId
  AND status = 'S3_UPLOADED'
  AND worker_id IS NOT NULL AND lease_until < now()
  AND s3_uploaded_at IS NOT NULL;
```

> 说明：T12 恢复出的**无主** `GATEWAY_DELIVERING`（`worker_id` / `lease_until` 为空）**不被**本扫描当作 stale——本扫描只选 `worker_id IS NOT NULL` 的过期任务，因此该任务不会走 2c 的 T16。这类待续投任务交由投递阶段 Dispatcher 认领续投（见 `08-concurrency-model.md` 的两阶段 claim）；故步骤 2c 的 T16 只作用于**有主**的过期 `GATEWAY_DELIVERING`。

步骤 2c：`GATEWAY_DELIVERING` 且 lease 过期 → `WAITING_RETRY`（迁移 T16）。

```sql
-- 示意，非 DDL 交付物
UPDATE transfer_task
SET status         = 'WAITING_RETRY',
    worker_id      = NULL,
    claimed_at     = NULL,
    lease_until    = NULL,
    retry_count    = retry_count + 1,
    next_retry_at  = now() + :backoff,
    error_code     = 'UNKNOWN_OUTCOME',
    updated_at     = now()
WHERE id = :taskId
  AND status = 'GATEWAY_DELIVERING'
  AND worker_id IS NOT NULL AND lease_until < now();
```

### 残留状态 → 恢复目标判定表（`s3_uploaded_at` 决定）

| 残留 `status` | `s3_uploaded_at` | 恢复目标 | 迁移 | 说明 |
|---|---|---|---|---|
| `UPLOADING` | `NULL` | `READY` | **T10** | 崩溃点 1 / 2；重新 claim 后重传，覆盖同一 Object |
| `UPLOADING` | `IS NOT NULL` | `S3_UPLOADED` | **T8**（补提交） | 异常组合（正常同一事务写入），告警；不得回退重传 |
| `S3_UPLOADED` | `IS NOT NULL` | `GATEWAY_DELIVERING` | **T12** | S3 已成功，直接进入投递 |
| `S3_UPLOADED` | `NULL` | 无对应迁移 | 见【架构问题】CR-3 | 正常不可达；告警并按数据异常处理 |
| `GATEWAY_DELIVERING` | 任意（期望 `IS NOT NULL`） | `WAITING_RETRY` | **T16** | 崩溃点 3；`error_code = UNKNOWN_OUTCOME` |

> `s3_uploaded_at` 是恢复目标的**唯一判别依据**：它为 `NULL` 说明 S3 阶段未确认完成，必须回到上传路径；它非 `NULL` 说明 S3 阶段已确认完成，只能向前推进，**禁止回退到 `UPLOADING`**（`GATEWAY_DELIVERING → UPLOADING` 属非法迁移）。

### 影响行数为 0 的处理

| 情况 | 含义 | 处理 |
|---|---|---|
| 影响行数 = 1 | 本恢复者成功推进任务 | 写 `transfer_event`，记指标 `recovery.transition.*` |
| 影响行数 = 0 | 任务已被别的 worker 接管、或状态已被别的恢复者推进、或 lease 已被续租 | **放弃本次动作**，不抛异常、不重试本条、不计失败；记 `recovery.conditional_update.skipped` |
| 影响行数 > 1 | 不可能（主键定位单行） | 视为实现缺陷，告警 |

条件更新是并发安全的根本：它把「读-判断-写」压缩为单条原子语句，从而不需要分布式锁，也不需要 `SELECT ... FOR UPDATE` 长事务。

## 优雅关闭（SIGTERM）

收到 `SIGTERM`（容器 `docker stop`、`systemctl stop`、Ctrl-C）后执行以下 **6 步**，顺序不可颠倒：

```text
1. 停止新扫描        —— 关闭 Scanner 调度，不再产生新任务
2. 停止 claim        —— 关闭 claim 通道，不再领取新任务（在途任务不受影响）
3. 等待在途 worker   —— 等待正在执行的 worker 结束，带超时
4. 尽量完成当前上传  —— 在超时预算内让当前 S3 PUT / Gateway 调用自然完成
5. 释放 lease        —— 对已主动放弃的任务清空 worker_id / claimed_at / lease_until
6. 退出              —— 关闭线程池、连接池、DataSource
```

### 超时与强杀兜底

- 步骤 3 / 4 受一个总超时约束（建议 `graceful.shutdown.timeout`，取值参考 `lease.duration` 的量级，需与 `lease.duration` 一起确定）。
- 超时后**强制退出**：不等待剩余 worker，直接进入进程终止。
- 强杀前若来不及执行步骤 5（释放 lease），**不影响正确性**：未释放的 lease 会在 `lease_until` 到达后过期，由下次启动的 Recovery 按 T10 / T12 / T16 接管。这就是「lease 兜底」的含义——优雅关闭是优化，不是正确性的必要条件。
- 若能在超时内完成步骤 5，则恢复更快（无需等待 lease 自然过期），属于性能优化。
- 超时强杀期间正在执行的 S3 PUT / Gateway 调用，其真实结果未知，按崩溃点 2 / 崩溃点 3 处理。
- 关闭过程中**禁止**删除任何任务、**禁止**写入任何失败终态（无 `FAILED` 状态可用）。

## 恢复不变量

| 不变量 | 在崩溃场景下如何保持 |
|---|---|
| **I3** 失败任务永不自动删除；无 `FAILED` 终态 | 恢复路径只做状态迁移（T10 / T12 / T16）与 lease 清理，**从不执行 `DELETE`**；崩溃点 4 中 `retry_count` 无限增长但任务始终存在；状态集合固定 9 个，`FAILED` 不存在，因此实现上无法「置失败」；1 年保留清理只作用于终态 `DELIVERED` / `CANCELLED`，`WAITING_RETRY` 等非终态永不进入清理（I7） |
| **I4** S3 上传成功但 DB 状态未知 → 重启后允许重传（覆盖同一 Object） | 崩溃点 1 / 2 均落到同一判定（`UPLOADING` + lease 过期 + `s3_uploaded_at IS NULL`）→ T10 → 重传；S3 Object Key 不含版本号且不启用对象版本控制，覆盖写收敛到同一对象；不因「S3 可能已存在」而跳过上传，因此不存在「跳过导致文件缺失」的路径 |
| **I5** Gateway 结果未知 → 重试，允许重复投递 | 崩溃点 3 一律 T16 → `WAITING_RETRY`，写 `error_code = UNKNOWN_OUTCOME`，不猜测上次是否成功；到 `next_retry_at` 后 T17 重新投递；重复投递由业务接受；S3 对象始终是当前版本，客户最终拿到最终版本内容 |

### 核心原则

> **不猜测上次是否成功，直接重执行。**

这条原则同时覆盖三种不确定性：

1. 「S3 PUT 是否成功」不确定 → 重传（覆盖同一 Object）。
2. 「Gateway 是否已投递」不确定 → 重投（允许重复投递）。
3. 「DB 事务是否已提交」不确定 → 以 DB 读到的状态为准，条件更新只依赖 DB 当前状态，不依赖客户端记忆。

只有一条例外约束：**终态不可复活**（T22 / T23），恢复流程不得触碰 `DELIVERED` / `CANCELLED` 任务。

## 恢复正确性论证

### 为什么「At-least-once + 条件更新 + lease」足以保证不丢文件

1. **持久性**：任务状态持久化在 PostgreSQL（唯一 Source of Truth）。JVM 崩溃只丢内存，不丢任务。因此任何已创建的任务在崩溃后依然存在且可被查询。
2. **所有权可判定**：任何进入执行阶段（`UPLOADING` / `GATEWAY_DELIVERING`）的任务都必须持有 lease（`worker_id` / `claimed_at` / `lease_until`）。JVM 崩溃后不再续租，`lease_until` 在有限时间后必然小于 `now()`，因此「谁在跑」始终可判定，不存在「状态不确定且无人负责」的任务。
3. **恢复可达**：lease 过期后，Recovery 用条件更新把任务推进到可执行状态（T10 / T12 / T16）。因为状态只可能是 9 个之一，而其中只有 3 个可能成为 stale 残留，恢复映射是完备的，不存在「残留状态无法恢复」的情形（除 `S3_UPLOADED` + `s3_uploaded_at IS NULL` 这一正常不可达组合，见 CR-3）。
4. **单一推进者**：条件更新（`WHERE id=? AND status=? AND worker_id IS NOT NULL AND lease_until < now()`）在数据库层保证同一任务同一时刻只有一个恢复者能推进（影响行数 1 vs 0）。因此不需要分布式锁，也不会出现「两个恢复者互相覆盖状态」。
5. **重执行是幂等/可覆盖的**：S3 覆盖写、Gateway 重复投递都是业务允许的（At-least-once）。重执行的**副作用上界**是「一次冗余传输」或「一次重复投递」，而不是「文件丢失」。
6. **不丢的充分性**：由 1–5，任一非终态任务在崩溃后必然在有限时间内回到可执行状态并被重新执行；而重新执行只会重复，不会跳过。因此「文件不因中转应用故障而永久丢失」成立。

### 最坏恢复时间上界

```
最坏恢复时间上界 = lease.duration + 调度周期
```

推导：

- 崩溃发生在任意时刻，此时任务的 `lease_until` 最多还剩 `lease.duration`（因为 lease 每次续租后固定为 `now() + lease.duration`；若续租周期为 `lease.duration / 3`，崩溃时剩余租期仍 ≤ `lease.duration`）。
- lease 过期后，Recovery 在下一个调度周期内发现该任务（扫描周期为「调度周期」），完成条件更新。
- 因此从崩溃到任务重新进入可执行状态的最坏耗时 = `lease.duration + 调度周期`。
- 之后的任务执行时间（S3 重传 / Gateway 重投）与外部系统可用性相关，不计入「恢复时间」；若 Gateway 持续不可用，则由崩溃点 4 的无限重试继续承担。
- 若崩溃发生在优雅关闭超时强杀场景，且步骤 5（释放 lease）未执行，同样落入上述上界；若已释放 lease，则恢复更快（无需等待自然过期）。

## 崩溃恢复测试矩阵

| # | 崩溃点 | 注入方式 | 期望恢复结果 | 验证断言 |
|---|---|---|---|---|
| 1 | 崩溃点 1 | 大文件 S3 PUT 进行中 `kill -9` | `UPLOADING` →（lease 过期）T10 → `READY` → 重传 → `S3_UPLOADED` | `status` 最终 ∈ {`S3_UPLOADED`,`GATEWAY_DELIVERING`,`DELIVERED`}；S3 对象存在；`transfer_task` 数量不变；无 `FAILED` |
| 2 | 崩溃点 1 | Testcontainers / S3 代理在 PUT 中途断网，客户端超时后 `kill -9` | 同 #1 | 恢复后 S3 对象 size == NAS 文件 size；无重复 `file_version` |
| 3 | 崩溃点 1 | 在 `UPLOADING` 状态提交前挂起进程（`SIGSTOP`）并让 lease 过期后恢复 | 原 worker 的续租失败退出；任务由 Recovery 接管 | 条件更新影响行数：恢复者 1，原 worker 续租 0；原 worker 不覆盖恢复后状态 |
| 4 | 崩溃点 2 | S3 PUT 返回 200 后、DB `COMMIT` 前 `kill -9` | `UPLOADING` → T10 → 重传覆盖同一 Object | S3 对象内容正确且唯一；未启用版本控制，对象版本数 = 1；`transfer_attempt` 有 2 条 UPLOAD 记录 |
| 5 | 崩溃点 2 | 代理丢弃 S3 的 200 响应，客户端超时后 `kill -9` | 同 #4 | `s3_uploaded_at` 非 NULL 后才进入 Gateway；无重复 S3 对象 Key |
| 6 | 崩溃点 3 | Gateway 已投递成功但响应被代理丢弃，随后 `kill -9` | `GATEWAY_DELIVERING` → T16 → `WAITING_RETRY` → T17 → 重投 → `DELIVERED` | 客户 SFTP 至少一次收到该文件；`transfer_attempt` 含 `UNKNOWN_OUTCOME`；重复投递被接受 |
| 7 | 崩溃点 3 | Gateway 服务端 sleep（请求已收，未响应）时 `kill -9` | 同 #6 | 恢复后重投；`retry_count` 恰好 +1；`next_retry_at` 按退避推进 |
| 8 | 崩溃点 3 | Gateway 返回 200 且客户端已读到，但在写 DB 前 `kill -9` | 同 #6（不猜测，重投） | 最终 `DELIVERED`；不出现「跳过重投」；无 `FAILED` |
| 9 | 崩溃点 4 | 代理把 Gateway 请求全部置为 503，持续 24h | 任务持续 `WAITING_RETRY` ↔ `GATEWAY_DELIVERING` 循环，永不终止 | 24h 后任务仍存在；`retry_count > max_retry_count`；`status` ∈ {`WAITING_RETRY`,`GATEWAY_DELIVERING`}；无删除、无 `FAILED` |
| 10 | 崩溃点 4 | 在 #9 基础上恢复 Gateway 可用 | 下一次 T17 成功 → T13 → `DELIVERED` | `completed_at` 非空；客户 SFTP 有最终版本文件 |
| 11 | 崩溃点 4 | PostgreSQL 容器重启（退避计时期间） | 任务与 `next_retry_at` 不丢 | 重启后 `retry_count`、`next_retry_at` 与重启前一致；以 DB 时间为准 |
| 12 | 通用（优雅关闭） | `SIGTERM` 于 `UPLOADING` 中 | 尽量完成上传；超时强杀后由 lease 兜底恢复 | 无重复 task；无孤儿 `worker_id`；恢复后状态可推进 |
| 13 | 通用（优雅关闭） | `SIGTERM` 于 `GATEWAY_DELIVERING` 中 | 释放 lease 或由 Recovery T16 接管 | 任务最终 `DELIVERED` 或 `WAITING_RETRY`（不丢） |
| 14 | 通用（恢复幂等） | 在 Recovery 执行过程中再次 `kill -9` | 重跑恢复无副作用 | 状态不变；重复执行条件更新影响行数为 0；无异常抛出 |
| 15 | 通用（恢复并发） | 模拟两个恢复者同时处理同一 stale 任务 | 仅一方成功推进 | 一方影响行数 1、另一方 0；状态单调不倒退；`recovery.conditional_update.skipped` 计数 +1 |
| 16 | 通用（启动顺序） | 冷启动并观察启动日志顺序 | 严格 `DB 连接 → 配置校验 → Recovery → Retry/Dispatcher + Worker → Scanner` | Scanner 首次扫描发生在 Recovery 完成之后；启动期间无新任务与恢复任务抢 worker |
| 17 | 通用（Scanner 崩溃） | Scanner 扫描 1000 个文件中途 `kill -9` | 重启后重扫，已入库的不重复 | `transfer_task` 无重复（`UNIQUE(file_version_id)`）；剩余文件被继续发现 |
| 18 | 通用（终态保护） | 对 `DELIVERED` / `CANCELLED` 任务执行恢复扫描 | 恢复流程不触碰终态 | 条件更新影响行数为 0；`status` 保持终态；记录告警（T22/T23 被尝试） |

---

## 【架构问题】标注

### 【架构问题】CR-1：启动顺序中「配置校验」与「DB 连接」的相对位置不一致

- **问题**：本文件按基线 §7 与任务要求采用强制顺序 `DB 连接 → 配置校验 → Recovery → Retry/Dispatcher + Worker → Scanner`；而整体设计 §84 的顺序是 `Load configuration → Validate configuration → Connect PostgreSQL → ... → Recover stale tasks`，即配置校验先于 DB 连接。
- **风险**：两处顺序不一致时，实现者可能按 §84 先校验配置再连库，导致「DB 不可用」与「配置错误」两种启动失败的判定顺序与日志/退出码语义不统一，测试用例难以对齐。
- **建议方案**：以基线为准，明确「配置加载与静态校验可在 DB 连接前完成，但**依赖 DB 的配置校验（如目录配置引用完整性）与 Recovery 必须在 DB 连接之后**」；或在 §84 补注本文件顺序。
- **对现有设计的影响**：不改变任何业务逻辑与状态机；仅影响启动流程的编排与失败处理顺序，属低风险。

### 【架构问题】CR-2：恢复判定强依赖 `transfer_task.s3_uploaded_at`，但该列在基线中仍为「建议，需确认」

- **问题**：基线 §4.4 把 `transfer_task.s3_uploaded_at` 列为「冗余/派生列（建议，需确认）」，而本文件的 T10 / T12 / T16 / T17 / T18 判定与「残留状态 → 恢复目标」映射**完全依赖该列**。
- **风险**：若最终不落该列，恢复算法将退化为「靠 `transfer_attempt` 历史推断 S3 是否成功」，既违反「不猜测」原则，又增加查询复杂度与竞态窗口。
- **建议方案**：将 `s3_uploaded_at` 升级为必需列，并与 `status='S3_UPLOADED'` 在同一事务写入。
- **对现有设计的影响**：数据模型需补一列与对应索引（配合 `(status, lease_until)`）；不影响状态集合与迁移编号。

### 【架构问题】CR-3：`S3_UPLOADED` + `s3_uploaded_at IS NULL` 组合无对应恢复迁移

- **问题**：迁移矩阵中 T12 的守卫要求 `s3_uploaded_at IS NOT NULL`，而 T10 的 From 是 `UPLOADING`。若观测到 `status='S3_UPLOADED'` 且 `s3_uploaded_at IS NULL`（正常构造下不可达，但列新增/回填/人工改库后可能出现），恢复算法无法在不新增迁移的前提下推进该任务。
- **风险**：该残留任务可能永久卡在 `S3_UPLOADED`，既不重传也不投递，违反「不丢文件」。
- **建议方案**：明确该组合为数据异常，恢复流程记录告警并按「保守回退重传」处理——但这需要状态机显式允许一条 `S3_UPLOADED → READY`（或 `S3_UPLOADED → UPLOADING`）的恢复迁移，属于迁移矩阵变更，需主设计确认。
- **对现有设计的影响**：若采纳需在 §2 迁移矩阵新增一条恢复迁移（与 T10 同族）；若不采纳，需在实现中以启动期数据校验 + 告警替代，并接受该异常组合下的人工介入。

---

## 小结

- 4 个崩溃点全部收敛到同一套原语：**DB 持久状态 + lease + 条件更新 + 重执行**。
- 恢复流程**永不删除任务、永不产生失败终态**；状态集合恰好 9 个，失败任务永久停留在 `WAITING_RETRY` 并无限重试。
- 最坏恢复时间上界 = `lease.duration + 调度周期`；启动顺序强制 `DB → 配置 → Recovery → Worker → Scanner`；优雅关闭超时由 lease 兜底。
