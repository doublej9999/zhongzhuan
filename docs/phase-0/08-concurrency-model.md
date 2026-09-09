# 08 Concurrency Model（并发模型）

> 权威事实源：`phase0-baseline.md`（下称「基线」）。本文与基线冲突时以基线为准。
> 已确认架构：`NAS → S3 → B2B Gateway → SFTP 中转系统整体技术设计.md`（下称「整体设计」）。
> 本文中的 SQL 一律标注为**示意，非 DDL 交付物**，仅用于精确表达并发语义。

---

## 本部分范围

本文定义 V1（单机部署、单实例）下「NAS → S3 → B2B Gateway → 客户 SFTP」链路的**并发执行模型**，覆盖：

1. 并发的分层结构（全局阶段并发 → 目录级并发 → DB claim 批量 → 单任务单 worker）与「有效并发」计算公式。
2. 限流要保护的四类稀缺资源，以及与 1000 文件/天、5GB/天、单文件 ≤10MB 的规模对齐。
3. DB Worker Claim 的语义：`FOR UPDATE SKIP LOCKED`、候选谓词、claim 与写 lease 的同一事务约束。
4. 同路径串行守卫（`NOT EXISTS` 子查询）与 supersede（基线 §3）的配合。
5. Lease 模型：字段、续租、过期判定、为什么不能把 claim 做成业务状态、续租失败的处置。
6. 有界队列与背压：DB 作为唯一 backlog，不把 backlog 拉进 JVM。
7. 线程模型选型（传统有界平台线程池 vs Virtual Threads + Semaphore）。
8. 并发正确性论证：不变量 I1/I2/I6 在并发下成立的逐条论证与三个竞态时序。
9. 并发风险清单与禁止的并发反模式。

**前置约束（来自基线，不可协商）**：

- 状态集合**恰好 9 个**：`DISCOVERED`、`STABILITY_CHECK`、`READY`、`UPLOADING`、`S3_UPLOADED`、`GATEWAY_DELIVERING`、`WAITING_RETRY`、`DELIVERED`、`CANCELLED`。**不得新增 `CLAIMED` / `PROCESSING` / `FAILED` / `SUPERSEDED`**。
- PostgreSQL 是唯一 Source of Truth（任务状态 + 持久化队列 + 重试依据 + 崩溃恢复依据 + 审计依据）。
- 一致性语义 **At-least-once**：允许重复上传、重复投递；**不允许丢失**。
- 单机单实例、单文件单 worker、不做文件内部并行（整体设计 §42）。

---

## 本部分不做什么

- **不做** 多实例/多节点并发与分布式锁设计（明确不引入分布式锁；多实例下扫描互斥是基线 A8 的已知缺口，V1 只保留扩展点）。
- **不做** Multipart 上传的内部并行（单文件 ≤10MB，V1 使用普通 PutObject；未来大文件再启用）。
- **不做** S3 对象版本控制、文件内容 hash、分布式事务、消息队列（Kafka/RabbitMQ/Redis）。
- **不做** 背压的监控告警阈值定义（属于 `11-monitoring` 范围）。
- **不做** supersede 算法的完整定义（属于基线 §3 与对应文档；本文只定义它与 claim 并发的交互）。
- **不做** 崩溃恢复流程的完整状态迁移（属于基线 §7；本文只定义 lease 过期判定与并发边界）。
- **不写** 业务代码、不创建 `*.java` / `*.sql` / `pom.xml` / `mvnw`；本文所有 SQL 均为说明性片段。
- **不决定** 具体线程数以外的 JVM 调优参数（如 GC、`-Xmx`）。

---

## 对应整体设计文档章节

| 本文小节 | 整体设计章节 | 关系 |
|---|---|---|
| 并发层次 | §43 并发控制、§72 推荐的并发结构 | 直接落实「有界、可配置并发」与 `min(global, directory)` |
| 为什么必须限流 | §44 为什么要限制并发 | 展开四类资源被打爆的具体后果 |
| DB Worker Claim | §15 Worker Claim、§14 为什么不把 worker claim 做成状态 | 落实 `FOR UPDATE SKIP LOCKED` + 同一事务写 lease |
| 同路径串行守卫 | §19 同一路径版本串行、§20 数据库约束、§38 实现、§39 version_no、§91 实现细节 | 落实串行门与「Gateway(N) 读到 N+1」竞态防护 |
| Lease 模型 | §16 Lease、§17 Crash Recovery | 落实 lease 独立于业务状态与续租 |
| 有界队列与背压 | §45 Scheduler 与 Worker 解耦、§46 Dispatcher、§47 背压 | 落实「小批量拉取 + 有界队列」 |
| 线程模型选型 | §70 Java 21 技术建议、§71 Virtual Threads 是否使用 | 落实「ThreadPoolExecutor + 有界队列」与「Virtual Thread ≠ 无限并发」 |
| 并发正确性论证 | §69 关键不变量 | 逐条论证不变量 1/2/6 |
| 并发风险清单 / 反模式 | §44、§47、§70、§91 | 风险与禁止项 |

---

## 并发层次

### 四层结构

并发被**四层闸门**逐层收窄。任何一层不通过，任务就不进入下一层，且**不消耗下层资源**。

| 层 | 名称 | 载体 | 默认值 | 作用域 | 不通过时的行为 |
|---|---|---|---|---|---|
| L1 | 全局阶段并发 | `Semaphore`（每阶段一个） | `worker.upload.maxConcurrency=8`、`worker.gateway.maxConcurrency=8` | JVM 进程 | 阻塞等待或放弃本次动作，任务保持已 claim 状态（受 lease 约束） |
| L2 | 目录级并发 | `Semaphore`（每 directory 一个） | `directory.max_concurrency` 2~4 | 单个 NAS 目录 | 同上；防止单个大目录占满全局许可 |
| L3 | DB claim 批量 | `claim-batch-size` + 条件 UPDATE | 建议 4~16 | 单次 claim 事务 | 减少一次事务内的行锁持有量与排队任务数 |
| L4 | 单任务单 worker | `UNIQUE(file_version_id)` + 行锁 + 串行守卫 | 1 | 单个 `file_version` | 已违反则不产生第二个 task；被 SKIP LOCKED 跳过 |

层级关系（text 框图）：

```text
Scanner（只写 DB）
   │
   ▼
PostgreSQL  ← 唯一 backlog（READY / WAITING_RETRY）
   │
   ▼
Dispatcher ── L3: claim-batch-size（单事务 claim + 写 lease）
   │
   ▼
Bounded Executor（有界队列；队列满 → 停止 claim）
   │
   ▼
L1: 阶段 Semaphore(upload=8 / gateway=8)
   │
   ▼
L2: 目录 Semaphore(max_concurrency=2~4)
   │
   ▼
L4: 单任务单 worker（一个 file_version 同时只有一个执行者）
```

### 有效并发公式

对阶段 `s ∈ {upload, gateway}`、目录 `d`：

```text
有效并发(s, d) = min( worker.<s>.maxConcurrency , directory[d].max_concurrency )

进程内阶段在途上限(s) = min( worker.<s>.maxConcurrency , Σ_d directory[d].max_concurrency )
```

补充说明：

- L1 与 L2 是**独立的两把闸门**，必须同时持有才能发起 IO；`min` 表示取更严格者。
- L2 的许可必须在任务**真正开始该阶段 IO 之前**获取、在 IO 结束后立即释放（见风险 R7 许可泄漏）。
- 上传阶段与投递阶段是**两套独立计数**，互不占用对方的许可。

### 示例计算

配置：`worker.upload.maxConcurrency=8`、`worker.gateway.maxConcurrency=8`，目录 20 个，`max_concurrency` 取 2~4。

| 场景 | 计算 | 结论 |
|---|---|---|
| 目录 A（`max_concurrency=4`） | `min(8, 4)` | 上传并发 4、投递并发 4 |
| 目录 B（`max_concurrency=2`） | `min(8, 2)` | 上传并发 2、投递并发 2 |
| 全部 20 个目录同时活跃 | `min(8, Σ(2~4)=40~80)` | 全局上传并发仍为 **8**；全局投递并发仍为 **8** |
| 进程内最大在途 IO 动作 | `8（上传） + 8（投递）` | 最多 **16** 个并发 IO 动作（不含 Scanner / 调度线程） |
| 单目录最大 NAS 读取并发 | `min(8, 4)` | **4** 个流式读句柄同时打开 |

**结论**：全局 8 是硬上限，目录 2~4 是局部上限；任一目录无论有多少文件积压，最多同时占用 4 个上传许可，因此**单个大目录无法饿死其他目录**（这也是目录级并发存在的唯一理由）。

### 吞吐校验（对齐 1000 文件/天）

- 稳态：1000 文件/天 ≈ 0.012 文件/秒；8 并发在单文件 1~3 秒完成时可支撑约 2.6~8 文件/秒，余量 **200 倍以上**。
- 峰值：假设 1000 文件集中在 1 小时内到达 → 0.28 文件/秒；8 并发余量约 **9~28 倍**。
- 带宽：5GB/天 ≈ 58KB/s 平均；若集中在 10 分钟 → ≈ 8.3MB/s，8 并发 × 1.25MB/s 刚好覆盖，说明 8 是**合理下限**而非瓶颈。
- 内存：单文件 ≤10MB，8 并发 × 10MB ≈ 80MB 上界（流式读取时更低），对 JVM 堆无压力。
- 结论：**不需要更高的并发**；提高并发只会增加资源风险，不会提高业务吞吐。

---

## 为什么必须限流

整体设计 §44 指出：即使每天只有 5GB，也可能出现 1000 个文件**短时间集中出现**。无限并发会同时打爆四类资源。

| 资源 | 无限并发的具体后果 | 观测信号 | 与规模的对齐 |
|---|---|---|---|
| **NAS IO（SMB/CIFS）** | 大量并发打开文件流 → SMB 会话/文件句柄耗尽、网络带宽打满、NAS 服务端请求队列溢出 → `STATUS_NETWORK_SESSION_EXPIRED`、读超时、目录枚举变慢。同一 SMB 连接上的 Scanner 也被拖慢，导致扫描周期漂移 | `Too many open files`、SMB 错误码、扫描耗时上升 | 单目录最多 1 万文件、单文件 ≤10MB；目录并发 2~4 足以喂满链路而不压垮 NAS |
| **S3 连接** | 无界并发 PUT → HTTP 连接池耗尽、TLS 握手风暴、S3 返回 `429 SlowDown` / `503` → 触发重试放大；每次 PUT 在 SDK 缓冲上占用数 MB，100 并发即可吃掉 1GB 堆 | 连接池等待时长、`SlowDown` 计数、堆增长 | 5GB/天；8 并发 × 10MB 缓冲 ≈ 80MB 上界 |
| **Gateway 请求** | B2B Gateway 通常有 QPS / 并发配额；无界并发 → `429`/`503`/连接被拒 → 大量 `UNKNOWN_OUTCOME`，在 At-least-once 下**放大重复投递**，甚至触发对方风控封禁 | `UNKNOWN_OUTCOME` 比例、4xx/5xx 计数 | 1000 次投递/天，对方接口通常远低于 8 QPS 稳态 |
| **JVM 线程与 socket** | 每任务一个平台线程（栈 512KB~1MB）+ 若干 socket → 数千线程即 1~2GB 栈内存，fd 耗尽 → `OutOfMemoryError: unable to create native thread` / `Too many open files`；无界队列还会把任务堆积到堆内 → GC 停顿 → 续租线程延迟 → **lease 误过期 → 双重执行** | 线程数、fd 数、GC 停顿、`lease_lost_total` | 单机部署，可用资源有限；16 个并发 IO 动作是可控量级 |

**限流的本质**：把「尽可能快」表达为**足够高但有上限的并发**（整体设计 §44），而不是无限并发。上限同时是**故障隔离边界**——S3 慢或 Gateway 慢时，积压留在 PostgreSQL（可持久、可观测），而不是扩散成 JVM 内的线程/内存爆炸。

**反向论证（为什么不干脆串行）**：单线程串行时，一个 10MB PUT 耗时 3 秒 → 1000 文件需 50 分钟；若 Gateway 单次超时 30 秒，一次超时即阻塞整条链路。并发 8 把最坏情况下的尾延迟从「线性累加」变成「按并发分片」，是 At-least-once 重试能够收敛的前提。

---

## DB Worker Claim（FOR UPDATE SKIP LOCKED）

### 候选谓词（示意，非 DDL 交付物）

```sql
-- 示意，非 DDL 交付物
-- 说明：<同路径串行守卫> 见下一小节
-- 上传候选 ∪ 投递候选（两阶段 claim 语义，见下方点 5「两条独立 Dispatcher 通道」）
SELECT t.id, t.file_id, t.version_no, t.s3_uploaded_at
FROM   transfer_task t
WHERE  (   -- 上传候选：READY ∪ 无 S3 且退避到期的 WAITING_RETRY
           (t.status = 'READY')
        OR (t.status = 'WAITING_RETRY'
            AND t.s3_uploaded_at IS NULL
            AND (t.next_retry_at IS NULL OR t.next_retry_at <= now()))
       )
   OR (   -- 投递候选：有 S3 且退避到期的 WAITING_RETRY ∪ 无主 GATEWAY_DELIVERING
          -- 无主 GATEWAY_DELIVERING（worker_id IS NULL）= T12 恢复出的待续投任务
          (t.status = 'WAITING_RETRY'
           AND t.s3_uploaded_at IS NOT NULL
           AND (t.next_retry_at IS NULL OR t.next_retry_at <= now()))
       OR (t.status = 'GATEWAY_DELIVERING' AND t.worker_id IS NULL)
       )
  AND  <同路径串行守卫>                                             -- 同 file_id 串行门
ORDER BY t.priority, t.created_at                                  -- 优先级 + 先进先出
FOR UPDATE SKIP LOCKED                                             -- 行锁 + 跳过已被抢占的行
LIMIT ?;                                                           -- claim-batch-size
```

要点逐条：

1. **候选按阶段分流（两阶段 claim 语义）**：`READY` 与**无 S3**（`s3_uploaded_at IS NULL`）且退避到期的 `WAITING_RETRY` 走**上传**；**有 S3**（`s3_uploaded_at IS NOT NULL`）且退避到期的 `WAITING_RETRY`，与**无主 `GATEWAY_DELIVERING`**（`worker_id IS NULL`，即 T12 恢复出的待续投任务）走**投递**。`UPLOADING` / `S3_UPLOADED` 恒有主、不可再 claim；只有**有主**的 `GATEWAY_DELIVERING`（`worker_id` 非空、lease 活跃）才表示「已被某 worker 拥有」、不可再 claim。`DISCOVERED` / `STABILITY_CHECK` 未稳定；`DELIVERED` / `CANCELLED` 是终态。**不引入 `CLAIMED`**——claim 是运行时所有权，不是业务状态。
2. **`next_retry_at IS NULL OR next_retry_at <= now()`**：`READY` 的 `next_retry_at` 为 `NULL`（立即执行）；`WAITING_RETRY` 必须等退避到期。时间基准取**数据库时钟**（`now()`），避免应用时钟漂移导致提前/延后重试。
3. **`ORDER BY priority, created_at`**：先高优先级，同优先级先进先出。这是**唯一**的公平性来源；不依赖自增 ID 的业务语义。
4. **`FOR UPDATE SKIP LOCKED LIMIT ?`**：先加行锁再取走，且**跳过**已被其他事务锁住的行。
5. **阶段分流**：claim 后按 `s3_uploaded_at` 决定进入上传还是投递阶段——`IS NULL` → 上传 Semaphore；`IS NOT NULL` → 投递 Semaphore。建议实现为**两条独立 Dispatcher 通道**（各自带 `AND t.s3_uploaded_at IS NULL` / `IS NOT NULL` 谓词），比在 UPDATE 里用 `CASE` 分流更清晰、也更容易监控各阶段 backlog。

### claim 与写 lease 必须同一事务

```sql
-- 示意，非 DDL 交付物
BEGIN;

-- ① 锁定并选中候选任务（上一条 SELECT ... FOR UPDATE SKIP LOCKED）
-- ② 同一事务内立即把所有权与状态写入

UPDATE transfer_task
   SET status      = CASE
                       WHEN status = 'READY'                            THEN 'UPLOADING'          -- T6
                       WHEN status = 'WAITING_RETRY'
                            AND s3_uploaded_at IS NULL                  THEN 'UPLOADING'          -- T18
                       WHEN status = 'WAITING_RETRY'
                            AND s3_uploaded_at IS NOT NULL              THEN 'GATEWAY_DELIVERING' -- T17
                       WHEN status = 'GATEWAY_DELIVERING'
                            AND worker_id IS NULL                       THEN 'GATEWAY_DELIVERING' -- T12 恢复的待续投：保持业务状态，仅认领（写 worker/lease）以继续投递
                     END,
       worker_id   = :worker_id,
       claimed_at  = now(),
       lease_until = now() + :lease_duration,
       updated_at  = now()
 WHERE id = ANY(:claimed_ids)
   AND status IN ('READY', 'WAITING_RETRY', 'GATEWAY_DELIVERING')  -- 乐观并发：候选态（含无主 GATEWAY_DELIVERING）
   AND (status <> 'GATEWAY_DELIVERING' OR worker_id IS NULL)       -- 仅认领无主（worker_id IS NULL）的 GATEWAY_DELIVERING
   AND (next_retry_at IS NULL OR next_retry_at <= now());

-- 影响行数 != :claimed_ids 长度 → 抛异常回滚整个事务（不允许部分 claim）
COMMIT;
```

为什么必须同一事务：

- 若「改状态」与「写 lease」分两个事务，中间存在窗口：状态已是 `UPLOADING` 但 `worker_id`/`lease_until` 为空。此时恢复流程的 stale-lease 判定**无法区分**「刚被 claim 但还没写 lease」与「worker 崩溃」——前者会被误判为 stale 并重新入队，导致同一任务被两个执行者持有。
- 反之若先写 lease 再改状态，则存在 `worker_id` 已设置但状态仍为 `READY` 的窗口，另一个 worker 会 claim 同一行（`status IN (...)` 仍匹配），造成双重持有。
- 同一事务 + 行锁 + `AND status IN (...)` 条件 UPDATE，使「选中」与「占有」原子化。所有迁移仍必须经 `TaskStateMachine.transition(...)` 校验（基线 §2 实现约束）。

### SKIP LOCKED 如何避免两个 worker 抢同一任务

- **无 `SKIP LOCKED` 时**：worker A 锁住行 R；worker B 的 `SELECT ... FOR UPDATE` **阻塞**直到 A 提交，然后（READ COMMITTED）重新检查该行，发现状态已不是候选态 → 0 行。正确但代价是**串行化等待**：批量 claim 时多个 worker 会排队在同一批行上，吞吐被锁等待吃掉。
- **有 `SKIP LOCKED` 时**：worker B 直接**跳过**被 A 锁住的行，继续寻找其他可 claim 的行。同一行在任一时刻最多被一个事务锁定 → **不可能被两个 worker 同时取走**；同时把「争用」转化为「各领各的活」，避免锁排队。
- `SKIP LOCKED` **不保证**公平性：它只保证互斥。公平性由 `ORDER BY priority, created_at` 与单机单实例的调度顺序提供。
- 单机单实例下仍需这套机制：进程内有多个内部 worker，`SKIP LOCKED` 是它们之间的互斥原语；同时为未来水平扩展保留正确的数据模型（整体设计 §15）。

---

## 同路径串行守卫

### 守卫子查询（示意，非 DDL 交付物）

```sql
-- 示意，非 DDL 交付物：<同路径串行守卫>
NOT EXISTS (
    SELECT 1
    FROM   transfer_task prev
    WHERE  prev.file_id     = t.file_id
      AND  prev.version_no  < t.version_no                 -- 更早的版本
      AND  prev.status NOT IN ('DELIVERED', 'CANCELLED')   -- 且仍未终结
)
```

即：**同 `file_id` 下不存在 `version_no` 更小且状态 ∉ {`DELIVERED`,`CANCELLED`} 的任务**时，本任务才可被 claim。与基线 §3.2 逐字一致。

### 完整 claim（守卫 + 锁）

```sql
-- 示意，非 DDL 交付物
-- 上传候选 ∪ 投递候选（投递候选含无主 GATEWAY_DELIVERING = T12 恢复待续投）
SELECT t.id
FROM   transfer_task t
WHERE  (   -- 上传候选：READY ∪ 无 S3 且退避到期的 WAITING_RETRY
           (t.status = 'READY')
        OR (t.status = 'WAITING_RETRY'
            AND t.s3_uploaded_at IS NULL
            AND (t.next_retry_at IS NULL OR t.next_retry_at <= now()))
       )
   OR (   -- 投递候选：有 S3 且退避到期的 WAITING_RETRY ∪ 无主 GATEWAY_DELIVERING
          (t.status = 'WAITING_RETRY'
           AND t.s3_uploaded_at IS NOT NULL
           AND (t.next_retry_at IS NULL OR t.next_retry_at <= now()))
       OR (t.status = 'GATEWAY_DELIVERING' AND t.worker_id IS NULL)
       )
  AND  NOT EXISTS (
         SELECT 1
         FROM   transfer_task prev
         WHERE  prev.file_id    = t.file_id
           AND  prev.version_no < t.version_no
           AND  prev.status NOT IN ('DELIVERED', 'CANCELLED')
       )
ORDER BY t.priority, t.created_at
FOR UPDATE SKIP LOCKED
LIMIT ?;
```

> 注意：整体设计 §38 的示例用 `previous.id < t.id` 表达「更早」。本文按基线 §3.2 采用 `version_no`，理由见【架构问题】C1。

### 它如何防止「v1 Gateway 读 S3 时 v2 覆盖对象」

S3 Object Key 不含版本（`<customer-space>/<relative-path>`，基线 §5），因此 v2 上传会**覆盖** v1 的同一 Object。危险窗口是：

```text
v1 GATEWAY_DELIVERING（Gateway 正在读 S3 对象）
        ‖
v2 UPLOADING（覆盖同一 S3 Object）
```

守卫通过如下链条关闭该窗口：

1. v1 处于 `GATEWAY_DELIVERING`（非终态）→ v2 的守卫子查询命中 v1 → v2 **连 `UPLOADING` 都进不去**，保持 `READY` 排队。
2. v1 只有在 `DELIVERED`（Gateway 明确成功）或 `CANCELLED`（被 supersede）之后才从子查询中消失。
3. 因此 v2 的 S3 PUT 在时间上**必然晚于** v1 的 Gateway 调用结束——不存在「v1 读对象时 v2 覆盖对象」的并发窗口。
4. 配合 §3.5 的第 2 条规则（宽限期 ≥ Gateway readTimeout + lease.duration + 5m），即使 v1 的请求在崩溃时已在途，supersede 发生时该请求也**必然**已超时结束。
5. 残余风险（基线 A1，业务已接受）：Gateway 服务端处理时间 > readTimeout 且最终成功，或恢复后旧请求仍在服务端生效，客户可能先收到新版本或收到一次重复投递。这是 At-least-once 的固有代价。

### 与 supersede 的配合关系

守卫是**必要但不充分**的解阻塞条件：若 v1 永久失败并停在 `WAITING_RETRY`，v2 会被守卫**永久阻塞**。基线 §3.2 明确指出：**唯一**的解阻塞途径就是 supersede 把 v1 置为 `CANCELLED`。

| 环节 | 守卫的作用 | supersede 的作用 |
|---|---|---|
| claim 前 | 阻止 v2 越过非终态的 v1 | 周期评估，判断 v1 是否「够老够坏」 |
| v1 活跃执行中 | 天然阻塞 v2 | **不 supersede**（活跃 lease 禁止，基线 §3.3 第 4 步）→ 等 lease 过期 → T10/T16 → 下一轮再评估 |
| v1 停在 WAITING_RETRY | 持续阻塞 v2 | `retry_count >= afterFailedAttempts` **或** 等待超过 `afterWaiting`，且超过 `gracePeriod` → v1 → `CANCELLED` + `cancel_reason=SUPERSEDED_BY_NEWER_VERSION` |
| v1 → DELIVERED | 守卫自动放行 v2 | 无需介入（终态） |

- 守卫与 supersede 必须**同时实现**：只实现守卫 → 旧版本永久失败会永久阻塞新版本；只实现 supersede → 活跃执行期仍可能被覆盖（S3 竞态）。
- 最坏等待上界（基线 §3.4）＝`lease.duration + max(supersede.afterWaiting, supersede.afterFailedAttempts × 退避上界) + 一个调度周期`，默认代入 ≈ **5~6 小时**（与 `02-nfr.md`、`04-`§6.4、`13-`§3.4 一致）。对从未执行（无退避）的旧版本，收紧为 `lease.duration + max(gracePeriod, afterWaiting) + 一个调度周期`（≈36 分钟）。
- supersede 复用 `CANCELLED` 终态，**不新增状态**（无 `SUPERSEDED`）。

---

## Lease 模型

### 字段（独立于业务状态）

| 字段 | 语义 | 写入时机 | 清除时机 |
|---|---|---|---|
| `worker_id` | 当前持有该任务的 worker 标识 | claim 事务内（T6/T17/T18） | 任务进入终态；或恢复流程重新入队 |
| `claimed_at` | 本次所有权的起始时刻 | claim 事务内 | 同左 |
| `lease_until` | 所有权的失效时刻（`claimed_at + lease.duration`，由**数据库时钟**计算） | claim 事务内，之后由续租刷新 | 终态；或恢复流程判定 stale 后 |

三条核心原则：

1. **业务状态与运行时所有权分离**（整体设计 §14）。`status` 回答「业务进展到哪一步」；`worker_id`/`lease_until` 回答「谁现在拥有它」。二者生命周期不同：状态由任务推进改变，所有权由 worker 存活决定。
2. **lease 不参与状态机迁移**，因此不占用 9 个状态中的任何一个。状态集合**恰好 9 个**。
3. **时间基准统一取数据库时钟**。claim 与续租都用 `now() + :lease_duration` 在 SQL 内计算，避免应用节点与 DB 时钟漂移造成「两个 worker 都认为自己持有 lease」。

### 续租

```sql
-- 示意，非 DDL 交付物
UPDATE transfer_task
   SET lease_until = now() + :lease_duration,
       updated_at  = now()
 WHERE id = :task_id
   AND worker_id = :worker_id            -- 所有权必须仍属于我
   AND status IN ('UPLOADING', 'S3_UPLOADED', 'GATEWAY_DELIVERING')  -- 仍在执行阶段
   AND lease_until > now();              -- 我的 lease 尚未过期
-- 影响行数 = 0 → 已失去所有权（被恢复流程接管或被 supersede）
```

- **续租周期建议 `lease.duration / 3`**：`lease.duration=5min` → 每 100 秒续租一次。留 3 次重试机会，可容忍单次续租失败与短暂 DB 抖动。
- **独立续租线程**：单线程 `ScheduledExecutorService` 或专用调度器，**不复用业务线程**。业务线程被 S3/Gateway 阻塞时不得让续租饿死。
- **续租动作不得在业务 IO 的临界区内执行**（尤其不得在持有 `synchronized` 块或同一 HTTP 连接时续租）。
- **续租时机**：在任务的关键阶段边界续租（S3 PUT 前、Gateway 调用前、每次 attempt 完成后），并保证 `lease.duration` ≥ 单次阶段动作的最坏耗时。

### lease 过期判定

```text
stale ⟺ lease_until IS NOT NULL
      AND lease_until < now()
      AND status IN ('UPLOADING', 'S3_UPLOADED', 'GATEWAY_DELIVERING')
```

恢复动作必须**条件更新**（基线 §7）：

```sql
-- 示意，非 DDL 交付物
UPDATE transfer_task
   SET status = 'READY', worker_id = NULL, claimed_at = NULL, lease_until = NULL
 WHERE id = :task_id
   AND status = 'UPLOADING'
   AND s3_uploaded_at IS NULL
   AND lease_until < now();
-- 影响行数 = 0 → 说明有活跃 worker 或状态已变化，放弃本次恢复
```

对应基线迁移：T10（`UPLOADING` → `READY`）、T12（`S3_UPLOADED` → `GATEWAY_DELIVERING`）、T16（`GATEWAY_DELIVERING` → `WAITING_RETRY`）。

### 为什么不用 CLAIMED / PROCESSING 状态

| 若引入 `CLAIMED`/`PROCESSING` | 后果 |
|---|---|
| 违反基线 §1「状态集合恰好 9 个」 | 状态机、迁移矩阵、审计、监控全部要改 |
| 状态爆炸 | 需要 `CLAIMED_UPLOADING`、`CLAIMED_GATEWAY`… 才能保留「业务进展到哪一步」 |
| 崩溃恢复无法决策 | 恢复时必须知道「下一步是重传 S3 还是重投 Gateway」，这由 `UPLOADING` vs `S3_UPLOADED` vs `GATEWAY_DELIVERING` 决定（T10 vs T12 vs T16）；`CLAIMED` 会丢失该信息 |
| 与「业务状态」语义混杂 | `CLAIMED` 是运行时所有权，worker 死亡时它并不代表业务失败；把它写进业务状态会让 supersede 与重试语义变模糊 |
| 无法表达「同任务被接管」 | lease 可以自然过期并被接管；业务状态一旦写成 `CLAIMED` 就需要额外的「谁有权把它改回来」规则 |

### 续租失败的处理

续租影响行数为 0（或抛异常且重试后仍失败）时，**已失去所有权**，必须按以下顺序处置：

1. **立即停止发起新的业务动作**——不再发新的 S3 PUT、不再发新的 Gateway 请求、不再开始新的文件读取。
2. **放弃任务**——**不写任何业务状态变更**。因为所有权已不属于本 worker，任何写入都可能覆盖接管者或被状态机的条件更新拒绝（影响行数 0）。
3. **尽力关闭在途 IO**——关闭输入流、取消 HTTP 请求；但**不猜测** S3 PUT / Gateway 调用是否已在服务端生效（At-least-once 语义，基线 §7 与 T15）。
4. **记录指标与日志**：`lease_lost_total{worker,stage}`，附 `task_id`、`file_id`、`version_no`。
5. **交给恢复流程**：任务停留在原执行状态，`lease_until` 已过期 → 恢复流程按 T10 / T12 / T16 处理；若它仍不终结，最终由 supersede 收敛（基线 §3.4）。

关键点：**放弃 ≠ 回滚**。worker 不试图「撤销」自己做过的事，只保证不再做新的事。这使续租失败与 JVM 崩溃走**同一条**恢复路径，减少分支。

---

## 有界队列与背压

### 机制框图（text）

```text
┌──────────────────────────────────────────────────────────────┐
│ PostgreSQL = 唯一 backlog（持久、可观测、容量不受 JVM 限制）    │
│   READY / WAITING_RETRY                                       │
│   （1000 文件/天 → 稳态 backlog 通常为个位数~几十）             │
└──────────────────────────────────────────────────────────────┘
        ▲                                        │
        │ 只读（不搬运）                           │ ①
        │                                        ▼
        │                    ┌───────────────────────────────────┐
        │                    │ Dispatcher（单线程，固定周期）      │
        │                    │                                   │
        │                    │ cap  = queue.remainingCapacity()  │
        │                    │ if cap < claim-batch-size:        │
        │                    │     本轮不 claim，sleep 一个 tick  │
        │                    │ n    = min(claim-batch-size, cap) │
        │                    │ claim n 条（单事务 + 写 lease）     │
        │                    │ executor.execute(task) × n        │
        │                    │ 拒绝 → 条件回滚 READY + 清 lease    │
        │                    └───────────────────────────────────┘
        │                                        │ ②
        │                                        ▼
        │                    ┌───────────────────────────────────┐
        │                    │ Bounded Executor                  │
        │                    │   core = max = maxConcurrency     │
        │                    │   queue = ArrayBlockingQueue(N)   │
        │                    │   队列满 = 背压信号（不丢弃任务）    │
        │                    └───────────────────────────────────┘
        │                                        │ ③
        │                                        ▼
        │                    ┌───────────────────────────────────┐
        │                    │ 阶段 Semaphore(8) → 目录 Sem(2~4)  │
        │                    │ → NAS 读 / S3 PUT / Gateway 调用   │
        └────────────────────└───────────────────────────────────┘
                             任务完成后状态回写 DB（终态或 WAITING_RETRY）
```

### 规则

1. **DB 是 backlog，不是 JVM 内存**。绝不允许 `SELECT 10000` 把整个 backlog 加载进堆（整体设计 §46/§47）。
2. **队列满即停止 claim**，而不是继续拉取或丢弃。背压信号是 `queue.remainingCapacity()`，不是异常。
3. **`claim-batch-size` 必须 ≤ 队列剩余容量**：`n = min(claim-batch-size, remainingCapacity)`。否则会出现「已 claim、已写 lease、却无法提交执行」的悬空任务（见【架构问题】C3）。
4. **提交被拒绝时必须回滚**：`executor.execute` 抛 `RejectedExecutionException` 时，立即在同一 worker 线程内条件更新回 `READY` 并清空 `worker_id`/`claimed_at`/`lease_until`（`WHERE id=? AND worker_id=? AND status IN ('UPLOADING','GATEWAY_DELIVERING')`）。否则 lease 泄漏，任务要等 `lease.duration` 后才被恢复。
5. **队列容量建议 `2 × maxConcurrency`**：队列只用于吸收「已 claim 但尚未拿到 Semaphore」的短暂排队，不是缓冲区。容量越大，lease 空转窗口越长。
6. **不做自旋式 claim**：Dispatcher 在队列满时 `sleep` 一个调度周期（例如 1 秒），不得忙等，否则会把 DB 查询变成负载源。
7. **优雅关闭**：① 停止 Scanner → ② 停止 claim → ③ 等待在途 worker（有超时）→ ④ 尽量完成当前上传 → ⑤ 释放 lease → ⑥ 退出（基线 §7）。队列中未执行的任务由其 lease 超时兜底。

---

## 线程模型选型

| 维度 | 传统有界平台线程池 | Virtual Threads + Semaphore |
|---|---|---|
| 实现 | `ThreadPoolExecutor(core=max=8, new ArrayBlockingQueue<>(16))` | `Executors.newVirtualThreadPerTaskExecutor()` + `Semaphore(8)` |
| 并发上限来源 | **线程数本身**即上限，队列满即背压 | **必须**靠 `Semaphore`，否则无限并发 |
| 吞吐（本系统） | 8~16 个并发 IO 动作，完全够用 | 理论可开数十万线程，但业务上限仍是 Semaphore(8)，**吞吐不提升** |
| 资源 | 16 × ~1MB 栈 ≈ 16MB，可控 | 线程内存极低，但 carrier 线程数受 `jdk.virtualThreadScheduler.parallelism` 限制 |
| 背压语义 | 显式（有界队列 + 拒绝策略） | 隐式（Semaphore 排队），队列语义需自行实现 |
| **Pinning 风险** | 无 | JDK 21 中**文件 IO 没有异步实现**：虚拟线程执行 `Files.newInputStream(...)` 读 NAS 时会**占用 carrier 线程**（pin），上传阶段收益近乎为零；`synchronized` 块内做 IO 同样 pin |
| 调试与可观测 | 线程栈、`jstack`、线程池指标成熟 | 虚拟线程栈在工具链中可读性较弱，Spring Boot 3.x 集成需注意版本 |
| 风险 | 线程池参数配错（无界队列 / `cachedThreadPool`） | 忘配 Semaphore → **无限并发**；pinning 导致吞吐抖动；团队熟悉度 |
| 推荐 | **V1 推荐** | 允许，但必须配 Semaphore，且不作为 V1 默认 |

**Virtual Thread ≠ 无限并发**（整体设计 §71）。虚拟线程只降低「线程本身的内存成本」，不降低 NAS 读句柄、S3 连接、Gateway 配额、socket 数量这些**真正的稀缺资源**。若无 `Semaphore` 兜底，`newVirtualThreadPerTaskExecutor()` 等价于无限并发。

```text
Virtual Threads
      │
      ▼
Semaphore(8)   ← 业务并发上限，与线程实现无关
      │
      ▼
S3 / Gateway / NAS
```

**V1 推荐：传统有界平台线程池 + `ArrayBlockingQueue`**，理由：

1. 并发上限固定为 8+8=16，平台线程成本可忽略，虚拟线程的内存优势用不上。
2. 上传阶段是**文件 IO**，JDK 21 虚拟线程在此会 pin carrier，收益为负。
3. 有界队列天然表达背压（整体设计 §47 的「队列满 → 停止拉取」），语义显式、易测。
4. 整体设计 §70 已建议 `ThreadPoolExecutor` + `BoundedBlockingQueue`，§71 明确「如果团队对 Virtual Threads 不熟，第一版使用传统有界线程池也完全合理」。
5. 可观测性更好：线程池活跃数、队列长度可直接映射为背压指标。

**未来切换条件**：JDK 提供异步文件 IO 或团队已验证 pinning 影响；届时仍**必须**保留 `Semaphore` 作为业务并发闸门，且上传阶段与投递阶段使用**独立** Semaphore。

---

## 并发正确性论证

### I1：一个 `file_version` 最多一个 `transfer_task`

- **防线**：`UNIQUE(file_version_id)`（基线 §4.2）+ `TaskStateMachine`。
- **并发论证**：两个 Scanner 线程同时发现同一 `file_version` 并各自 `INSERT transfer_task` → 其中一个提交成功，另一个收到唯一约束冲突（PostgreSQL `23505`）。正确做法是**捕获该异常并视为「已存在」**，读取既有 task 后继续，而不是让整个扫描周期回滚。
- **不变式方向**：任务创建只能发生在 T1（Scanner 创建 file_version 的同时），`DELIVERED`/`CANCELLED` 均**不删除**任务（I3/I7），因此「一个 version 一个 task」在整个生命周期内单调保持。
- **残余风险**：若唯一约束被误删或用 `INSERT ... ON CONFLICT DO NOTHING` 后未回读，会静默丢任务（违反「不丢文件」）。必须告警而非静默。

### I2：同一 `file_id` 同一时刻最多一个版本处于非终态执行阶段

- **防线**：claim 时的 `NOT EXISTS` 串行守卫 + 行锁 + 条件 UPDATE。
- **并发论证**：守卫是**声明式谓词**，其正确性依赖两条单调性质：
  - **P1**：`DELIVERED`/`CANCELLED` 是终态且不可复活（I8），因此「更早版本非终态」这个谓词只会从「真」变为「假」，**不会反向**。
  - **P2**：更早版本的任务行**先于**更晚版本存在（`version_no` 单调分配 + `UNIQUE(file_id, version_no)`）。
  - 由 P1：任何时刻只要更早版本非终态，后续所有 claim 都看不到它变回终态 → 守卫持续生效，v2 无法越过 v1。
  - 由 P2：v2 被 claim 时，v1 的行**一定已经存在**，守卫一定能看到它。若 P2 不成立（例如 `version_no` 缺失、退回用 `id` 排序），守卫会出现漏判（见【架构问题】C1、基线 A2/A3）。
- **注意**：守卫是**读谓词**而非锁，它保证的是「基于已提交状态的最优判定」。要获得严格的「同一 `file_id` 串行化」，需要在同一事务内对同 `file_id` 的候选行加锁或使用事务级 advisory lock（见【架构问题】C1）。

### I6：Scanner 重复运行不产生重复 task

- **防线**：`UNIQUE(file_id, fingerprint)` + `UNIQUE(file_id, version_no)` + `UNIQUE(file_version_id)`（基线 §4.2）。
- **并发论证**：三层唯一约束分别覆盖「重复 file_version」「版本序号冲突」「重复 task」。任意一层命中即由 DB 拒绝，Scanner 捕获 `23505` 后跳过。
- **Scanner 单飞**：V1 使用 `fixedDelay` 调度 + 进程内 `AtomicBoolean` 保证同一时刻只有一个扫描周期在跑（调度周期重叠是重复 insert 的主要来源）。多实例下需要 DB 级互斥，属基线 A8（V1 只留扩展点，不实现分布式锁）。
- **与 Worker 的隔离**：Scanner **只写 DB**，绝不直接调用 S3/Gateway（整体设计 §45）。因此 Scanner 与 Worker 之间**没有共享内存状态**，唯一交互点是 DB 行与串行守卫。

### 竞态 1：两个 worker 同时 claim

1. 任务 v1（`READY`）与 v2（`READY`）同属 `file_id=100`，`version_no` 1 与 2。
2. Worker A 开启事务 TA，执行 claim 语句；其语句快照中 v1 为 `READY`。
3. Worker B 开启事务 TB，执行同一 claim 语句。
4. TA 的 `FOR UPDATE` 锁住 v1 行；TB 的 `SKIP LOCKED` **跳过** v1 行，不阻塞。
5. TB 评估 v2 的守卫：子查询查到 v1 且 v1 非终态（无论 TA 是否已提交，见步骤 6 的两种情形）→ 守卫不成立 → **v2 不被 claim**。
6. 情形 a（TB 快照早于 TA 提交）：TB 看到 v1 = `READY`（非终态）→ 阻塞 v2。情形 b（TB 快照晚于 TA 提交）：TB 看到 v1 = `UPLOADING`（非终态）→ 阻塞 v2。**两种情形都安全**。
7. TA 提交：v1 → `UPLOADING` + `worker_id`/`claimed_at`/`lease_until`（同一事务）。
8. TB 事务内没有可 claim 的行 → 返回空批次，Dispatcher 本轮不做任何事。
9. 若 TA 的 `UPDATE ... AND status IN ('READY','WAITING_RETRY')` 影响行数为 0（v1 被第三方先改），TA 抛异常回滚——**绝不部分 claim**。
10. 结论：v1 与 v2 不可能同时进入执行阶段；I2 在「两个 worker 同时 claim」下成立，前提是 P2（版本序）成立。

### 竞态 2：Scanner 与 Worker 并发

1. Worker W1 持有 v1，状态 `GATEWAY_DELIVERING`，lease 活跃。
2. Scanner 周期启动，列出目录，观察到 file A 的 `(path, size, mtime)` 与上次**不一致** → 建立新 `file_version` v2（T1，`DISCOVERED`）。
3. Scanner **只写 DB**，不触碰 S3/Gateway，也不读取 v1 的运行时字段；因此不会与 W1 争夺任何 JVM 内资源。
4. 下一周期 Scanner 再次观察到 v2 的 `(path, size, mtime)` **完全一致** → T4 → v2 进入 `READY`。
5. Worker W2 尝试 claim v2 → 守卫子查询命中 v1（`GATEWAY_DELIVERING`，非终态）→ v2 保持 `READY`，不消耗任何 Semaphore 许可。
6. `SupersedeService` 评估：v1 的 lease **活跃** → 按基线 §3.3 第 4 步**不 supersede**，等待其 lease 过期或进入终态。
7. 分支 A（v1 → `DELIVERED`）：守卫自动放行 → v2 被 claim → 上传覆盖同一 S3 Object → 投递 v2。客户最终看到 v2（最新版本胜出）。
8. 分支 B（W1 崩溃 / lease 过期）：恢复流程按 T16 把 v1 → `WAITING_RETRY`；之后 supersede 条件（`retry_count`/`afterWaiting` + `gracePeriod`）满足 → v1 → `CANCELLED` → 守卫放行 → v2 执行。
9. 分支 C（v1 停在 `WAITING_RETRY` 无限重试）：`retry_count` 单调增长，**必然**在有限时间内越过阈值 → supersede 发生（基线 §3.4）。
10. 结论：Scanner 的并发只会**新增**非终态版本，不会绕过守卫；最新版本的最坏等待时间有上界。

### 竞态 3：supersede 与 claim 并发

1. 任务 v1 处于 `WAITING_RETRY`，`lease_until` 已过期。
2. Worker W 的 claim 事务 TC 执行 `SELECT ... FOR UPDATE SKIP LOCKED`，若先拿到 v1 的行锁，则 v1 被 W 锁定。
3. `SupersedeService` 的事务 TS 执行条件更新：
   ```sql
   -- 示意，非 DDL 交付物
   UPDATE transfer_task
      SET status = 'CANCELLED',
          cancel_reason = 'SUPERSEDED_BY_NEWER_VERSION',
          superseded_by_version_no = :new_version_no,
          cancelled_at = now(),
          updated_at = now()
    WHERE id = :old_id
      AND status IN ('DISCOVERED','STABILITY_CHECK','READY','WAITING_RETRY')  -- 见基线 §3.3
      AND (worker_id IS NULL OR lease_until < now())                          -- lease 不活跃
      AND (retry_count >= :afterFailedAttempts
           OR now() - COALESCE(last_attempt_finished_at, updated_at) >= :afterWaiting)
      AND now() - COALESCE(last_attempt_finished_at, updated_at) >= :gracePeriod;
   ```
4. **情形 a：TS 先提交。** v1 → `CANCELLED`（终态）。TC 随后：若 TC 的 `SELECT` 在 TS 提交后取快照 → v1 不在 `status IN ('READY','WAITING_RETRY')` 中 → 不 claim。若 TC 的 `SELECT` 在 TS 提交前取快照，则它可能选中 v1；此时 TC 的 `UPDATE ... AND status IN (...)` 在拿到行锁后重新求值，发现 `status='CANCELLED'` → 影响行数 0 → TC 回滚整个事务，**不 claim**。
5. **情形 b：TC 先提交。** v1 → `UPLOADING`/`GATEWAY_DELIVERING` + `worker_id` 与活跃 `lease_until`。TS 的 `WHERE ... AND (worker_id IS NULL OR lease_until < now())` 不成立 → 影响行数 0 → **TS 放弃本次 supersede**（记录日志与指标，下轮再评估）。
6. **情形 c：两者交错。** 行锁把二者串行化；先拿到锁的一方提交后，另一方的条件谓词必然重算并失败——**不可能出现「supersede 覆盖了活跃 worker」或「worker 覆盖了 CANCELLED 任务」**。
7. **复活检查。** 若 TS 已把 v1 置为 `CANCELLED`，而恢复流程仍在处理该行的 stale lease：恢复的条件更新含 `AND status='UPLOADING'`（或 `'GATEWAY_DELIVERING'`）→ 影响行数 0 → **不复活**（I8 成立）。
8. 结论：supersede 与 claim 的互斥由「行锁 + 条件 UPDATE + 影响行数判定」保证，二者都**不得**退化为无条件 `UPDATE ... WHERE id=?`。

---

## 并发风险清单

| # | 风险 | 触发条件 | 后果 | 缓解 | 残留风险 |
|---|---|---|---|---|---|
| R1 | claim 后排队导致 lease 空转 | claim 批量大于队列剩余容量，或队列容量过大 | 任务已被标记 `UPLOADING` 且 lease 在计时，但无人执行；lease 到期后恢复流程重复入队，可能被两个执行者先后持有 | `claim-batch-size ≤ remainingCapacity`；提交失败立即回滚 READY + 清 lease；队列容量取 `2 × maxConcurrency` | 极端 GC 停顿下仍有短暂空转（见【架构问题】C3） |
| R2 | 目录配额事后门控导致任务饥饿 | claim 批次跨目录，L2 只能在 JVM 侧等待 | 大目录占满全局许可，小目录任务长期排队；被阻塞任务的 lease 空转 | claim 时按 `directory_id` 轮转/过滤；对超配额任务立即回滚 READY | 单机单实例下饥饿只影响延迟，不影响正确性（见【架构问题】C2） |
| R3 | 续租线程被阻塞 / GC 停顿 → lease 误过期 | 长 STW、续租线程与业务共享线程池、DB 抖动超过 `lease.duration` | 同一任务被两个执行者持有 → 重复 S3 PUT、重复投递（At-least-once 允许重复，但不应由误判引发） | 独立续租线程；续租周期 `lease.duration/3`；`lease.duration` ≥ 阶段最坏耗时；监控 `lease_lost_total` | 超长 GC 或 DB 不可用时仍可能误过期 |
| R4 | 应用与 DB 时钟漂移 | `lease_until` 由应用计算 | 两个 worker 同时认为 lease 有效 → 双重持有 | **统一用数据库时钟**（`now() + :lease_duration`）计算 claim 与续租 | 无（DB 时钟是单点） |
| R5 | 串行守卫是读谓词而非锁 | 版本序不可靠（`version_no` 缺失，退回 `id`）或守卫漏判 | v1/v2 同时进入执行阶段 → 违反 I2 → Gateway 读到错误版本 | 强制 `version_no`；必要时对同 `file_id` 使用事务级 advisory lock 或子查询 `FOR UPDATE` | 见【架构问题】C1、基线 A2/A3 |
| R6 | supersede 与 claim 竞态 | 任一方退化为无条件 `UPDATE ... WHERE id=?` | supersede 覆盖活跃 worker，或 worker 覆盖 `CANCELLED` 任务（I8 被破坏） | 双方必须使用条件 UPDATE + 影响行数判定 + `status IN (...)` 与 lease 活跃性谓词 | 无（有行锁兜底），但**代码纪律**风险高 |
| R7 | Semaphore 许可泄漏 | 异常路径未 `release()`、`try/finally` 缺失、流未关闭 | 许可逐渐耗尽 → 有效并发降到 0 → 全链路停滞（不是失败，而是「不动」） | 一律 `try/finally` 释放；监控 `semaphore.availablePermits()` 并设告警阈值 | 需要监控覆盖才能发现 |
| R8 | claim 批次过大 | `claim-batch-size` 配置过高 | 单事务持锁时间长 → 行锁等待；批次内任务长时间排队 → R1 | 建议 4~16；与队列容量联动 | 无 |
| R9 | Scanner 周期重叠 | 调度周期 < 单次扫描耗时 | 并发 `INSERT` → 唯一冲突；若未捕获则整批回滚，可能漏扫 | `fixedDelay` + 进程内单飞标记；捕获 `23505` 视为已存在并回读 | 多实例下缺 DB 级互斥（基线 A8，V1 不实现） |
| R10 | 重复投递放大 | Gateway 超时/结果未知 + 高并发重试 | 客户 SFTP 收到重复文件（At-least-once 固有） | 退避 + 并发上限；`UNKNOWN_OUTCOME` 单独计数；幂等键待确认（基线 Q3） | 业务已接受重复投递 |
| R11 | Dispatcher 忙等 | 队列满时用自旋代替 sleep | DB 查询风暴 → 加剧负载 → 恶性循环 | 队列满时 `sleep` 一个调度周期 | 无 |
| R12 | 优雅关闭与恢复流程并发 | SIGTERM 时在途 worker 与 Recovery 同时处理同一任务 | 任务被「完成」与「重新入队」同时作用于一行 | 严格按基线 §7 关闭顺序；恢复动作条件更新（`AND lease_until < now()`） | 强杀时由 lease 兜底 |
| R13 | 双 Semaphore 持有顺序不当 | 同一 worker 连续执行「S3 上传 → Gateway 投递」时未先释放上传许可 | 有效并发低于 8+8，甚至形成等待环 | 先释放上传许可再获取投递许可；或按 T11「下轮 claim 恢复投递」把两阶段拆成两个独立执行单元 | 见【架构问题】C5 |

---

## 禁止的并发反模式

| 反模式 | 为什么禁止 | 正确做法 |
|---|---|---|
| `Executors.newCachedThreadPool()` | 线程数无上限（`Integer.MAX_VALUE`），任务激增时线程爆炸 → 栈内存耗尽、`unable to create native thread` | `new ThreadPoolExecutor(core=max=maxConcurrency, new ArrayBlockingQueue<>(2×maxConcurrency), ...)` |
| 无界队列（`LinkedBlockingQueue` 无参、`ConcurrentLinkedQueue` 当队列） | 队列永不拒绝 → 背压失效 → backlog 被拉进 JVM 堆 → GC 压力 → 续租延迟 → lease 误过期（R3） | 有界 `ArrayBlockingQueue`，队列满即停止 claim |
| 无超时的阻塞调用（S3 PUT / Gateway HTTP / NAS 读） | 一个卡死任务永久占用 Semaphore 许可与 lease，最终耗尽全部并发（R7 变体） | 显式设置 connect/read/write/总超时；超时按失败处理（T9/T14/T15，结果未知一律按失败） |
| 在事务中做 S3 / HTTP IO | 事务持有行锁与连接，IO 耗时秒级 → 锁等待、连接池耗尽、`idle_in_transaction_session_timeout` 被杀 | claim 事务只做「选行 + 写 lease」；IO 在事务外执行；状态回写用独立短事务 |
| 无界 `Semaphore`（`new Semaphore(Integer.MAX_VALUE)` 或不加信号量） | 等价于无限并发；`Virtual Thread ≠ 无限并发`（整体设计 §71） | `Semaphore(maxConcurrency)`，且与线程实现解耦 |
| 把 `SELECT ... WHERE status='READY'` 直接当队列 | 无行锁 → 多个 worker 取到同一行（双重执行）；无 `SKIP LOCKED` → 锁排队；无守卫 → 违反 I2 | 一律使用两阶段候选谓词（上传候选 ∪ 投递候选，含无主 `GATEWAY_DELIVERING`）+ 退避谓词 + 串行守卫 + `FOR UPDATE SKIP LOCKED LIMIT ?` + 同一事务写 lease |

**补充禁止项**：

- 禁止用内存队列替代 PostgreSQL 作为任务来源（DB 是唯一 Source of Truth）。
- 禁止在 `synchronized` 块内做网络/文件 IO（会 pin 虚拟线程，也会长时间持锁）。
- 禁止依赖 `Thread.sleep` 之外的忙等（自旋）做背压。
- 禁止在 worker 线程内直接修改 lease 之外的运行时字段来「标记」所有权（所有权只由 `worker_id`/`lease_until` 表达）。

---

## 【架构问题】汇总

### C1：串行守卫是读谓词，缺少 `file_id` 维度的显式串行化原语

- **问题**：基线 §3.2 与整体设计 §38 把串行守卫表达为 `NOT EXISTS` 子查询，它是一个**读谓词**而非锁。其正确性依赖两条未在文档中显式约束的性质：① 更早版本的任务行必先存在（依赖 `version_no` 单调，而 `file_version.version_no` 在基线 A2 中标注为「§39 建议但 §33 DDL 缺失」）；② 更早版本非终态状态不可逆。整体设计 §38 的示例用 `previous.id < t.id` 表达「更早」，与基线 §3.2 的 `version_no < 本版本` 语义不一致（基线 A3）。
- **风险**：若版本序不可靠（退回 `id`）或更晚版本的任务行先于更早版本出现，两个 worker 可能同时 claim 同一 `file_id` 的不同版本 → 违反 I2 → 直接触发基线 §3.5 / 整体设计 §91 的「Gateway(N) 读到 N+1」竞态。
- **建议方案**：① 强制 `file_version.version_no`（落实 A2）并把 `file_id`/`version_no` 冗余到 `transfer_task`（落实 A3）；② claim 事务内增加 `pg_try_advisory_xact_lock(hash(file_id))` 或对同 `file_id` 的候选行加锁后**复查**守卫，把「谓词判定」升级为「锁串行化」；③ 统一文档中的版本序表达为 `version_no`。
- **对现有设计的影响**：只增加一个事务内 advisory lock 或一次 `FOR UPDATE` 复查，不改变 9 个状态、迁移矩阵与 supersede 算法；`transfer_task` 需要 `file_id`/`version_no` 冗余列与 `(file_id, version_no)` 索引（基线 §4.3 已建议）。

### C2：目录级并发配额无法在 claim SQL 中表达

- **问题**：基线 §6 要求「目录级并发 2~4」，但 claim 是**跨目录的批量取行**，SQL 层无法表达「该目录是否还有配额余量」。目录 Semaphore 只能在 JVM 侧事后门控，导致任务被 claim 后可能长时间等待 L2 许可。
- **风险**：① 被阻塞的任务已写 lease，空转可能超过 `lease.duration` → 恢复流程重复入队（R1/R2）；② 单个大目录（最多 1 万文件）可能占满全局许可，其他目录饥饿。
- **建议方案**：① claim 时按 `directory_id` 分组或轮转（例如 `ROW_NUMBER() OVER (PARTITION BY directory_id)` 限制每组取 `max_concurrency` 条）；② 或对超出目录配额的任务**立即条件回滚** `READY` 并清 lease，让其他目录有机会；③ 至少保证 `claim-batch-size ≤ 队列剩余容量` 且 `lease.duration ≥ 队列最大排队时间 + 单任务最坏执行时间`。
- **对现有设计的影响**：只影响 claim SQL 与 Dispatcher 逻辑，不改变状态集合与迁移矩阵；需要在 `transfer_task` 上可快速取得 `directory_id`（可经 `file_id` 关联或冗余）。

### C3：claim 即迁移 `UPLOADING`（T6）与「先 claim 后执行」存在 lease 空转窗口

- **问题**：基线 T6 要求「Worker claim 成功，同一事务写 `worker_id`/`claimed_at`/`lease_until`」，即 claim 瞬间业务状态已进入 `UPLOADING`。而整体设计 §46/§47 要求「小批量拉取 + 有界队列」，意味着任务可能先入队、后执行。此时任务处于**非终态执行阶段但无人执行**：既占用串行门（阻塞同 `file_id` 的更新版本），又让 lease 开始计时。
- **风险**：lease 在排队期间到期 → 恢复流程把任务重新入队 → 同一任务被两个执行者先后持有；同时新版本被无意义地阻塞，延长「最新版本最坏等待上界」。
- **建议方案**：① 强制 `n = min(claim-batch-size, remainingCapacity)`；② `executor.execute` 被拒绝时**立即条件回滚** `READY` 并清 lease（不可依赖 lease 过期）；③ 明确 `lease.duration ≥ 队列最大排队时间 + 单任务最坏执行时间`（当前 5 分钟对 10MB 文件足够，但需写进配置约束）；④ 可选：把 lease 起点定义为「任务真正开始执行时」（需要修订 T6 措辞，属架构决策，本文不擅自修改）。
- **对现有设计的影响**：方案 ①②③ 不改变状态机；方案 ④ 会改变 T6 的语义，需主决策方确认。

### C4：目录并发配置的命名与位置不一致

- **问题**：基线 §6 写作 `directory.max_concurrency`（snake_case，暗示全局单值）；整体设计 §43/§72 写作 `directory.maxConcurrency`；整体设计 §5 的 `application.yml` 是 `nas.directories[].maxConcurrency`（每目录 camelCase）；整体设计 §33 DDL 是 `directory.max_concurrency`（列名）。四处命名/位置不同。
- **风险**：实现时出现「读的是全局配置、写的是目录配置」的错配，导致目录级并发静默失效（退化为只受全局 8 限制）。
- **建议方案**：统一为**每目录**配置（与整体设计 §5、§33 一致）：YAML 用 `nas.directories[].maxConcurrency`，DB 列用 `directory.max_concurrency`，文档叙述统一称「目录级并发 `directory.max_concurrency`（每目录，2~4）」，并明确「无目录级配置时回退到全局并发」。
- **对现有设计的影响**：纯命名/文档统一，无行为变化。

### C5：上传与投递两个 Semaphore 的获取/释放顺序未定义

- **问题**：整体设计 §48 的 Gateway worker 流程与基线 T11 允许「同一次 worker 执行继续投递」。若 worker 在持有上传许可的同时获取投递许可，两个独立计数的 Semaphore 会被同一任务同时占用，实际有效并发低于设计的 8+8；极端情况下多任务交叉持有可能形成等待环。
- **风险**：吞吐下降、并发上限失效、潜在的许可饥饿。
- **建议方案**：① 明确规定「**先释放上传许可，再获取投递许可**」，两阶段之间通过 DB 状态（`S3_UPLOADED`）衔接；② 或更彻底地采用 T11 的「下轮 claim 恢复投递」路径，把上传与投递拆成两个独立执行单元，天然不重叠持有。
- **对现有设计的影响**：只影响 worker 内部流程编排，不改变状态集合、迁移矩阵与 supersede 规则。

---

## 一句话总结

并发模型的全部复杂度都服务于一条主线：**同一 `file_id` 的版本必须串行，不同 `file_id` 可以并行，而并行的总量必须被四层闸门有界收窄**。PostgreSQL 承担 backlog 与互斥（`FOR UPDATE SKIP LOCKED` + 条件 UPDATE + lease），JVM 只承担有界执行（有界队列 + Semaphore）；`lease` 表达运行时所有权而**不**新增业务状态，状态集合始终保持**恰好 9 个**。
