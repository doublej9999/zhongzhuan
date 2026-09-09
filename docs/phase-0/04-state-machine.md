# 04 State Machine（状态机）

## 本部分范围

本部分定义 `TransferTask` 的**唯一权威状态集合、合法迁移矩阵、非法迁移清单，以及 supersede / latest-wins 规则**。

- 状态集合固定为 **9 个**，不得新增。
- 所有迁移必须经 `TaskStateMachine.transition(...)` 校验并落库。
- supersede（旧版本让位给新版本）是本部分的核心内容，直接回答"同一 NAS path 的旧版本永久 Gateway 失败时，如何保证最新版本不被阻塞"。

## 本部分不做什么

- 不写 Java 代码，不定义 `TaskStateMachine` 的方法签名实现。
- 不定义 DDL / migration 文件（表结构与约束见 `06-data-model.md`）。
- 不引入任何新状态（`CLAIMED` / `PROCESSING` / `FAILED` / `SUPERSEDED` 一律禁止）。
- 不改变已确认架构；发现冲突只标注 `【架构问题】`。

## 对应整体设计文档章节

| 整体设计章节 | 本部分对应内容 |
|---|---|
| §9 文件稳定性检测 | `DISCOVERED` → `STABILITY_CHECK` → `READY` |
| §12 文件不存在处理 | 取消规则（`CANCELLED`） |
| §13 状态机 | 状态集合与主链路 |
| §14 为什么不把 worker claim 做成状态 | 第 4 节：运行时 ownership 与业务状态分离 |
| §15–§16 Worker Claim / Lease | 第 4 节：claim 与 lease 字段 |
| §17 Crash Recovery | 恢复迁移 T10 / T12 / T16 |
| §19–§21 同路径串行 / 版本最终一致性 | 第 6 节：串行守卫与 latest wins |
| §24 Gateway 超时 | T15：结果未知按失败处理 |
| §25–§26 Retry / Exponential Backoff | T9 / T14 / T15 / T17 / T18 |
| §48–§51 Gateway Worker / 人工强制重试 | T13 / T19 |
| §53 状态迁移集中管理 | 第 3 节：`TaskStateMachine` 唯一入口 |
| §69 关键不变量 | 第 7 节：状态机不变量 |
| §91 最大的技术风险 | 第 6.6 节：S3 覆盖竞态 |

---

## 1. 状态集合（恰好 9 个）

```
DISCOVERED ──▶ STABILITY_CHECK ──▶ READY ──▶ UPLOADING ──▶ S3_UPLOADED ──▶ GATEWAY_DELIVERING ──▶ DELIVERED
                    │                  │                                          ▲    │
                    │                  │                                          │    │ 失败/超时/未知
                    └──────┬───────────┴──────────────▶ CANCELLED                 └────┴──▶ WAITING_RETRY
                           │                                  ▲                                    │
                           └──────── supersede ────────────────┘                                    │
                                                                        next_retry_at 到期 ◀────────┘
```

| 状态 | 语义 | 是否终态 | 允许被 supersede | 允许 manual retry |
|---|---|---|---|---|
| `DISCOVERED` | 首次发现该 file_version，已落库，尚未进入稳定判定 | 否 | ✅ | ❌（无意义） |
| `STABILITY_CHECK` | 稳定判定中（已观察一次或多次，等待下一次一致观察） | 否 | ✅ | ❌（无意义） |
| `READY` | 已稳定，可被 worker claim 执行 | 否 | ✅ | ❌（已在队列中） |
| `UPLOADING` | 已 claim，正在从 NAS 流式读取并 PUT 到 S3 | 否 | ❌（活跃 lease 期间） | ❌ |
| `S3_UPLOADED` | S3 PUT 成功且已提交；等待/进入 Gateway 投递 | 否 | ❌（活跃 lease 期间） | ❌ |
| `GATEWAY_DELIVERING` | 已 claim，正在调用 B2B Gateway | 否 | ❌（活跃 lease 期间） | ❌ |
| `WAITING_RETRY` | 本次尝试失败/结果未知，等待 `next_retry_at` 再次执行 | 否 | ✅ | ✅（见 6.7） |
| `DELIVERED` | Gateway **明确**返回成功（文件已落客户 SFTP） | **是** | ❌ **永不** | ❌ |
| `CANCELLED` | 主动取消（文件消失 / 目录禁用 / 被新版本 supersede） | **是** | ❌ | ❌（终态，见 6.7） |

> **不新增 `FAILED`**：`max_retry_count` 只是"进入长期重试"的阈值，超过后仍保持 `WAITING_RETRY`，永久重试直到成功。
> **不新增 `CLAIMED` / `PROCESSING`**：运行时 ownership 由 `worker_id` / `claimed_at` / `lease_until` 表达（见第 4 节）。

---

## 2. 迁移矩阵（权威）

| # | From | To | 触发条件 | 守卫条件 | 失败处理 |
|---|---|---|---|---|---|
| T1 | （无） | `DISCOVERED` | Scanner 首次发现符合后缀的文件，创建 `file_version` | 后缀命中、`directory.enabled = true` | 落库失败 → 本周期跳过，下周期重扫（无状态污染） |
| T2 | `DISCOVERED` | `STABILITY_CHECK` | 首次观察记录完成，进入稳定判定 | — | — |
| T3 | `STABILITY_CHECK` | `STABILITY_CHECK` | 再次扫描发现 `fingerprint` 变化（更新 `size`/`mtime`/`fingerprint`，重置稳定基线） | — | 视为新观察，不产生新 task |
| T4 | `STABILITY_CHECK` | `READY` | **相邻两次观察 `path + size + mtime` 完全一致** | 该 `file_version` 唯一（`UNIQUE(file_id, fingerprint)`）；写 `stable_at` | — |
| T5 | `STABILITY_CHECK` | `CANCELLED` | 稳定前文件从 NAS 消失 / 目录被禁用 / 被新版本 supersede | 未开始读取 NAS | 写 `cancel_reason` |
| T6 | `READY` | `UPLOADING` | Worker claim 成功，**同一事务**写 `worker_id` / `claimed_at` / `lease_until` | ① 同 `file_id` 无更早的非终态任务（除已被 supersede）；② 全局与目录级并发均有余量 | claim 未命中（`SKIP LOCKED`）→ 保持 `READY`，下轮再试 |
| T7 | `READY` | `CANCELLED` | 文件在开始读取前从 NAS 消失 / 目录禁用 / supersede | 未开始读取 NAS | 写 `cancel_reason` |
| T8 | `UPLOADING` | `S3_UPLOADED` | S3 PUT 返回成功**且** DB 状态提交成功 | 写 `s3_uploaded_at`；`attempt(S3_UPLOAD, success=true)` | PUT 失败/超时 → T9；DB 提交失败 → 保持 `UPLOADING`，由 lease 恢复 |
| T9 | `UPLOADING` | `WAITING_RETRY` | S3 失败 / 超时 / 网络中断 / 认证失败 / 5xx | `retry_count+1`、`next_retry_at=backoff(...)`、`s3_uploaded_at` 保持 `NULL` | — |
| T10 | `UPLOADING` | `READY` | 崩溃恢复：lease 过期且 `s3_uploaded_at IS NULL` | **条件更新**（`WHERE status='UPLOADING' AND lease_until < now()`） | 影响行数 0 → 任务已被接管，放弃本次恢复 |
| T11 | `S3_UPLOADED` | `GATEWAY_DELIVERING` | 同一次 worker 执行继续投递，或下轮 claim 恢复投递 | — | — |
| T12 | `S3_UPLOADED` | `GATEWAY_DELIVERING` | 崩溃恢复：lease 过期，S3 已成功 | `s3_uploaded_at IS NOT NULL` + 条件更新 | 影响行数 0 → 放弃 |
| T13 | `GATEWAY_DELIVERING` | `DELIVERED` | Gateway **明确**返回成功 | 同一事务写 `attempt(success=true)` + `completed_at` | — |
| T14 | `GATEWAY_DELIVERING` | `WAITING_RETRY` | Gateway 明确失败（4xx / 5xx） | `retry_count+1`、`next_retry_at=backoff(...)` | — |
| T15 | `GATEWAY_DELIVERING` | `WAITING_RETRY` | Gateway 超时 / 连接中断 / **结果未知** | 未知结果一律按失败处理；`error_code=UNKNOWN_OUTCOME` | 允许重复投递 |
| T16 | `GATEWAY_DELIVERING` | `WAITING_RETRY` | 崩溃恢复：lease 过期 | 条件更新 | 不猜测上次是否成功，直接重投 |
| T17 | `WAITING_RETRY` | `GATEWAY_DELIVERING` | `next_retry_at <= now()` 且 `s3_uploaded_at IS NOT NULL` | 串行守卫通过（第 6.2 节） | — |
| T18 | `WAITING_RETRY` | `UPLOADING` | `next_retry_at <= now()` 且 `s3_uploaded_at IS NULL` | 串行守卫通过 | — |
| T19 | `WAITING_RETRY` | `READY` | 人工强制重试（operator） | 该版本仍是该 file 的**最新稳定版本** | 不满足 → 拒绝并记录 operator action |
| T20 | `WAITING_RETRY` | `CANCELLED` | 被更新稳定版本 supersede | 三条守卫全过（§6.3）：lease 不活跃 **且**（失败次数或等待时间越界）**且** 宽限期已过 | 写 `cancel_reason`、`superseded_by_version_no` |
| T21 | `DISCOVERED` / `STABILITY_CHECK` / `READY` / `WAITING_RETRY` | `CANCELLED` | supersede 统一入口 | 见第 6.3 节三条守卫 | — |
| T22 | `DELIVERED` | 任意 | **禁止**（终态，不可复活） | — | 需重投 → 走 6.7 的 manual retry 策略或产生新版本 |
| T23 | `CANCELLED` | 任意 | **禁止**（终态） | — | 需重投 → 需显式策略确认 |

### 迁移矩阵速查（From → 允许的 To）

| From | 允许的 To |
|---|---|
| `DISCOVERED` | `STABILITY_CHECK`、`CANCELLED` |
| `STABILITY_CHECK` | `STABILITY_CHECK`、`READY`、`CANCELLED` |
| `READY` | `UPLOADING`、`CANCELLED` |
| `UPLOADING` | `S3_UPLOADED`、`WAITING_RETRY`、`READY`（仅恢复） |
| `S3_UPLOADED` | `GATEWAY_DELIVERING` |
| `GATEWAY_DELIVERING` | `DELIVERED`、`WAITING_RETRY` |
| `WAITING_RETRY` | `GATEWAY_DELIVERING`、`UPLOADING`、`READY`（仅 manual retry）、`CANCELLED`（仅 supersede） |
| `DELIVERED` | （无） |
| `CANCELLED` | （无） |

---

## 3. 非法迁移清单

以下迁移必须被 `TaskStateMachine` **拒绝**、抛出明确异常、记录 WARN 日志与 `transfer_event`（拒绝审计），且**不改变**任何状态。

| 非法迁移 | 原因 |
|---|---|
| `DISCOVERED` → `READY` | 必须经过 `STABILITY_CHECK`；稳定性不可跳过 |
| `STABILITY_CHECK` → `UPLOADING` | 未稳定不得执行 |
| `READY` → `S3_UPLOADED` / `READY` → `GATEWAY_DELIVERING` | 必须经过 `UPLOADING` |
| `UPLOADING` → `DELIVERED` | 必须经过 S3 与 Gateway |
| `UPLOADING` → `GATEWAY_DELIVERING` | 必须经过 `S3_UPLOADED`（保留审计粒度） |
| `WAITING_RETRY` → `DELIVERED` | 必须实际再次执行 Gateway |
| `GATEWAY_DELIVERING` → `UPLOADING` | S3 必须先于 Gateway；回退重传只能经 `WAITING_RETRY` |
| `DELIVERED` → 任意 | 终态不可复活 |
| `CANCELLED` → 任意 | 终态不可复活 |
| 任意 → `DISCOVERED` | `DISCOVERED` 仅作初始状态 |
| 任意 → `WAITING_RETRY`（除 `UPLOADING` / `GATEWAY_DELIVERING`） | `WAITING_RETRY` 只表达"执行失败/结果未知" |

### 状态迁移的并发与事务约束

1. **唯一入口**：业务代码禁止 `task.setStatus(...)`；只允许 `taskStateMachine.transition(task, target, reason, context)`。
2. **条件更新（乐观并发控制）**：每次迁移落库必须写成
   `UPDATE transfer_task SET status = :to, ... WHERE id = :id AND status = :from AND <lease 守卫>`；
   影响行数 `0` → 视为并发冲突，**放弃本次动作**（不重试同一动作，交由下一轮调度重新判断）。
3. **事务边界**：状态迁移 + 对应 `transfer_attempt` 写入 + `transfer_event` 写入**在同一事务内**提交。
4. **外部 IO 不在事务内**：S3 PUT 与 Gateway HTTP 调用**必须**在事务外执行；只把结果带回事务内做状态迁移（避免长事务与连接池耗尽）。
5. **租约与状态同事务**：claim 时写 `worker_id`/`claimed_at`/`lease_until` 与 `status` 必须同一事务（T6）。

---

## 4. 为什么 claim 不是状态

| 关注点 | 表达方式 |
|---|---|
| 业务进展 | `transfer_task.status`（9 个状态之一） |
| 运行时所有权 | `worker_id` |
| 何时被领取 | `claimed_at` |
| 所有权有效期 | `lease_until` |

- 好处：状态机保持简单；崩溃恢复只需判断"lease 是否过期"，无需为每个 worker 动作新增状态。
- 禁止：`CLAIMED` / `PROCESSING` / `IN_PROGRESS` / `LEASED` 等状态。
- 语义澄清：`UPLOADING` / `GATEWAY_DELIVERING` 表示"正在执行"，但**不等于**"有活跃 worker"——JVM 崩溃后状态仍停留在此，直到恢复流程依据过期 lease 迁移。

---

## 5. 状态与"下一步动作"的派生规则

`WAITING_RETRY` 同时承载"S3 待重传"与"Gateway 待重投"两种含义。为避免新增状态，下一动作由**派生字段**决定：

| 条件 | 下一动作 | 迁移 |
|---|---|---|
| `s3_uploaded_at IS NOT NULL` | 直接重投 Gateway | T17 `WAITING_RETRY` → `GATEWAY_DELIVERING` |
| `s3_uploaded_at IS NULL` | 重新上传 S3 | T18 `WAITING_RETRY` → `UPLOADING` |
| 崩溃残留 `UPLOADING` + lease 过期 | 重新上传 | T10 `UPLOADING` → `READY` |
| 崩溃残留 `S3_UPLOADED` + lease 过期 | 继续投递 | T12 `S3_UPLOADED` → `GATEWAY_DELIVERING` |
| 崩溃残留 `GATEWAY_DELIVERING` + lease 过期 | 重投 | T16 `GATEWAY_DELIVERING` → `WAITING_RETRY` |

> `s3_uploaded_at` 是本派生规则的关键列（见 `06-data-model.md` 4.4 与【架构问题】A4 相关讨论）。

---

## 6. supersede / latest wins 规则

> 本节回答提示词第十九节特别指定的问题：**"同一个 NAS path 的旧版本永久失败时，如何保证最新版本不会被阻塞"**。

### 6.1 问题陈述

同一 `file_id`（= 同一 NAS 逻辑路径）下存在多个 `file_version`：v1、v2、v3…。S3 Object Key 不含版本（`<customer-space>/<relative-path>`），因此：

- **必须串行**：v1 在 Gateway 读 S3 对象期间，v2 不得覆盖同一 Object。
- **又不能永久串行**：v1 若长期 Gateway 失败，业务只要求"当前版本最终可靠投递"，不能让 v1 永久阻塞 v2。

### 6.2 串行守卫（claim 的必要条件）

```sql
-- 示意，非 DDL 交付物
SELECT t.*
FROM transfer_task t
WHERE t.status IN ('READY', 'WAITING_RETRY')
  AND (t.next_retry_at IS NULL OR t.next_retry_at <= now())
  AND NOT EXISTS (
      SELECT 1
      FROM transfer_task older
      WHERE older.file_id = t.file_id
        AND older.version_no < t.version_no
        AND older.status NOT IN ('DELIVERED', 'CANCELLED')
  )
ORDER BY t.priority, t.created_at
FOR UPDATE SKIP LOCKED
LIMIT :batch;
```

**语义**：同 `file_id` 下，只要存在更早版本处于非终态，本版本**不可**进入 `UPLOADING` / `GATEWAY_DELIVERING`。

**推论**：旧版本永久失败会形成**永久阻塞**，因此必须存在一条把旧版本置为终态的路径 —— 那就是 supersede。

### 6.3 supersede 判定规则（可实现的精确算法）

**术语**

| 术语 | 定义 |
|---|---|
| `latest_stable(file)` | 该 `file_id` 下 `stable_at IS NOT NULL` 且状态非 `CANCELLED` 的**最大** `version_no` |
| 活跃 lease | `worker_id IS NOT NULL AND lease_until > now()` |
| `supersede.gracePeriod` | 默认 `gateway.readTimeout + lease.duration + 5m`，默认值 **15m** |
| `supersede.afterFailedAttempts` | 默认 **5** |
| `supersede.afterWaiting` | 默认 **30m** |

**算法**（由 `SupersedeService` 周期执行，并在 claim 前做一次校验）

1. 求 `latest_stable(file)` 对应任务 `T_new`，且 `T_new.status ∈ {DISCOVERED, STABILITY_CHECK, READY}`（即正在等待串行门）。
2. 找出同 `file_id` 下 `version_no` 更小的任务 `T_old`，`T_old.status ∈ {DISCOVERED, STABILITY_CHECK, READY, WAITING_RETRY}`。
3. `T_old` **同时**满足以下三条 → 迁移 `CANCELLED`（T20 / T21）：
   - **lease 不活跃**：`worker_id IS NULL OR lease_until < now()`
   - **失败或等待越界**：`retry_count >= supersede.afterFailedAttempts` **或** `now() - COALESCE(last_attempt_finished_at, updated_at) >= supersede.afterWaiting`
   - **宽限期已过**：`now() - COALESCE(last_attempt_finished_at, updated_at) >= supersede.gracePeriod`
4. 若 `T_old.status ∈ {UPLOADING, S3_UPLOADED, GATEWAY_DELIVERING}`：**不 supersede**，等待其 lease 过期 → 崩溃恢复把它转为 `READY`（T10）或 `WAITING_RETRY`（T16）→ 下一轮重新评估。
5. `DELIVERED` **永不** supersede；`CANCELLED` 保持不动。
6. supersede 落库：`status = CANCELLED`、`cancel_reason = SUPERSEDED_BY_NEWER_VERSION`、`superseded_by_version_no = latest_stable`、`cancelled_at = now()`，并写 `transfer_event`（`operator = system`）。

**哪些状态可被 supersede / 不可**

| 状态 | 可否 supersede | 说明 |
|---|---|---|
| `DISCOVERED` | ✅ | 未执行、无在途请求；仍须满足三条守卫（守卫自 `updated_at` 起算） |
| `STABILITY_CHECK` | ✅ | 未执行、无在途请求；仍须满足三条守卫（守卫自 `updated_at` 起算） |
| `READY` | ✅ | 未执行、无在途请求；仍须满足三条守卫（守卫自 `updated_at` 起算） |
| `WAITING_RETRY` | ✅（满足三条守卫） | 核心场景：旧版本长期失败 |
| `UPLOADING` | ❌（活跃 lease 期间） | 正在读 NAS / 写 S3；等 lease 过期后按 `WAITING_RETRY` 处理 |
| `S3_UPLOADED` | ❌（活跃 lease 期间） | 即将投递；等 lease 过期 |
| `GATEWAY_DELIVERING` | ❌（活跃 lease 期间） | **最关键**：请求在途，绝不能被覆盖竞态打断 |
| `DELIVERED` | ❌ **永不** | 已成功投递，是历史事实 |
| `CANCELLED` | ❌ | 已是终态 |

**正在 Gateway 中怎么办**

- 旧版本处于 `GATEWAY_DELIVERING` 且 lease 活跃：新版本**等待**，不进 `UPLOADING`。
- 旧版本的 Gateway 调用有 `gateway.readTimeout` 上界，调用结束即进入 `DELIVERED`（T13）或 `WAITING_RETRY`（T14/T15），随后走第 3 步。
- JVM 崩溃时，lease 过期 → T16 → `WAITING_RETRY` → 宽限期（≥ readTimeout + lease.duration + 5m）保证原请求**必然**已结束 → 再 supersede。
- **绝不**在 `GATEWAY_DELIVERING` + 活跃 lease 时强行 supersede。

**已 DELIVERED 的怎么办**

- `DELIVERED` 是终态与历史事实，**永不** supersede、**永不**自动重投。
- 若客户需要更早版本，属于业务例外，必须走人工流程（不在 V1 自动行为内）。

**manual retry 老版本怎么办**

| 情形 | 处理 |
|---|---|
| 该版本仍是 `latest_stable` 且状态 `WAITING_RETRY` | 允许 T19 `WAITING_RETRY` → `READY`，写 `transfer_event`（`operator = <操作人>`） |
| 该版本已不是 `latest_stable` | **拒绝**，错误码 `SUPERSEDED_BY_NEWER_VERSION`，仍写 operator action 审计 |
| 该版本已是 `CANCELLED` | **拒绝**（终态不可复活） |
| 该版本是 `DELIVERED` | **拒绝**（终态；重复投递需业务确认） |
| 该任务有活跃 lease | **拒绝**（避免与正在执行的 worker 冲突） |

### 6.4 为什么"最新版本一定不会被永久阻塞"（上界论证）

新版本卡住只可能有两个原因：**串行门**或**并发配额**。

- **并发配额**：只要全局/目录并发 > 0，配额不会永久耗尽（worker 会释放），不构成永久阻塞。
- **串行门**：由旧版本非终态引起，只能靠 supersede 解除。而：
  - 旧版本处于 `WAITING_RETRY` 时，每次失败使 `retry_count` 单调递增，**必然**在有限次尝试后越过 `afterFailedAttempts`；即使尝试间隔按指数退避增长，退避也有 `maxDelay`（1h）上界，且 `afterWaiting`（30m）提供第二条触发路径（按"距上次尝试时间"判定，而非次数）。
  - 旧版本处于活跃执行阶段时，`lease_until` 有限（`lease.duration` = 5m，续租不会无限延长崩溃后的 lease），JVM 崩溃或长时间执行都会让 lease 过期 → 进入第 4 步 → 第 3 步。
- **最坏等待时间上界**：

  ```
  T_worst = lease.duration                    (旧版本活跃执行最长占位)
          + max(supersede.afterWaiting,       (按时间触发)
                afterFailedAttempts × 退避上界) (按次数触发，退避上界 = maxDelay)
          + 一个 SupersedeService 调度周期
  ```

  默认值代入：`5m + max(30m, 5 × 1h=5h) + 调度周期` → 最坏约 **5~6 小时**，远小于"永久"。
- **结论**：supersede 保证最新稳定版本在**有限时间**内获得执行权；业务要求的"当前版本最终可靠投递"成立。

### 6.5 supersede 对 S3 覆盖语义的影响

- 被 supersede 的旧版本若**已经**上传过 S3，其对象就是同一个 Key，**无需清理**：新版本上传会覆盖它。
- 被 supersede 的旧版本**不再**投递，客户可能看不到该历史版本——这是业务明确允许的（"当前版本最终可靠投递比历史版本全部投递更重要"）。
- S3 最终内容 = 最后一次成功上传的版本；`DELIVERED` 记录表明"该版本曾被成功投递"。

### 6.6 S3 覆盖竞态与关闭窗口

竞态形态：

```
v1 GATEWAY_DELIVERING（请求在途，Gateway 正在读 S3 对象）
        ‖
v2 UPLOADING（PUT 覆盖同一 S3 Object）
```

**关闭竞态的两条规则（必须同时实现）**

1. **活跃 lease 期间禁止 supersede**（6.3 第 3 步第 1 条）：v1 在 `GATEWAY_DELIVERING` + 活跃 lease 时，v2 连 `UPLOADING` 都进不去。
2. **宽限期 ≥ Gateway read timeout + lease 时长**：即使 v1 的请求在崩溃时已在途，supersede 发生时该请求**必然**已超时结束。

**残余风险（接受，见 `13-open-questions-risks.md` 的 A1）**：若 Gateway 服务端处理时间超过 `readTimeout` 且最终成功，或恢复后旧请求仍在服务端生效，客户可能先收到新版本内容或收到一次重复投递。这属于 At-least-once 的固有代价，业务已明确接受。可选的进一步缓解（需 Gateway 契约支持）：投递请求携带对象标识（ETag/size）并由 Gateway 校验。

### 6.7 终态与人工干预的边界

| 场景 | 结论 |
|---|---|
| `DELIVERED` 是否可被 supersede | 否，永不 |
| `CANCELLED`（含被 supersede）是否可 manual retry | 否，终态不可复活（V1 行为）；若业务确需，需显式确认后单独设计 |
| manual retry 是否会绕过 latest wins | 不会，T19 守卫强制校验 `latest_stable` |
| supersede 是否会删除数据 | 不会，只改状态；attempt/event 历史全部保留 |

---

## 7. 状态机不变量

| # | 不变量 | 保证机制 |
|---|---|---|
| S1 | 状态只能是 9 个枚举值之一 | 列级约束（CHECK / 枚举） |
| S2 | 所有迁移经 `TaskStateMachine` 校验 | 唯一入口 + 非法迁移拒绝 |
| S3 | 迁移落库使用条件更新，影响行数 0 即放弃 | `WHERE id=? AND status=:from` |
| S4 | `DELIVERED` / `CANCELLED` 为终态，不可迁出 | 迁移矩阵 |
| S5 | 不存在 `FAILED` 终态；失败恒为 `WAITING_RETRY` | 迁移矩阵 |
| S6 | 同一 `file_id` 同时最多一个版本处于执行阶段 | 串行守卫（6.2） |
| S7 | `DELIVERED` 仅由 T13（Gateway 明确成功）产生 | 迁移矩阵 |
| S8 | 每次迁移必须伴随一条 `transfer_event` | 同事务写入 |
| S9 | 每次执行尝试必须伴随一条 `transfer_attempt` | 同事务写入 |

---

## 8. 测试要点（Phase 3 将展开）

| 类别 | 用例 |
|---|---|
| 合法迁移 | T1–T21 每条各一个正向用例，断言终态与 `transfer_event` |
| 非法迁移 | 第 3 节每条各一个用例，断言抛错且状态不变 |
| 并发更新 | 两个线程对同一任务条件更新，断言只有一个成功 |
| supersede | ① 未执行的 `READY`/`DISCOVERED` 旧版本在满足三条守卫后被取消（守卫自 `updated_at` 起算，**非立即**）；② `WAITING_RETRY` 旧版本越过阈值后被取消；③ `GATEWAY_DELIVERING` + 活跃 lease 时**不**取消；④ lease 过期后被取消；⑤ `DELIVERED` 永不被取消 |
| latest wins | 旧版本被 supersede 后新版本可 claim；旧版本不再被 claim |
| manual retry | ① 最新版本允许；② 非最新版本拒绝；③ `DELIVERED` / `CANCELLED` 拒绝；④ 活跃 lease 拒绝 |
| 恢复迁移 | T10 / T12 / T16 三种残留状态各一个用例 |

---

## 9. 【架构问题】

### 【架构问题】A2：`file_version.version_no` 在 §39 建议但 §33 DDL 缺失

- **问题**：串行守卫与 supersede 判定都依赖 `version_no` 的确定性排序；§33 的 `file_version` DDL 没有该列，§39 才建议增加。
- **风险**：若靠 `id` 隐含排序，语义脆弱（并发插入、清理后重建）。
- **建议方案**：在 `file_version` 增加 `version_no`，`UNIQUE(file_id, version_no)`，由 Scanner 在同一事务内按 `max(version_no)+1` 分配。
- **对现有设计的影响**：Phase 2 DDL 增加一列一约束；状态机与 supersede 规则不变。

### 【架构问题】A5：`file_version.status` 与 `transfer_task.status` 双状态并存

- **问题**：整体设计 §33 给 `file_version` 定义了 `status`，§34 给 `transfer_task` 也定义了 `status`，两者都取值于同一套状态名，事实来源不清。
- **风险**：两处状态可能不一致（例如 `file_version.status=READY` 而 task 已 `DELIVERED`），运维与查询会给出矛盾答案。
- **建议方案**：明确 `transfer_task.status` 为**唯一权威任务状态**；`file_version.status` 仅表达发现/稳定语义（`DISCOVERED` / `STABILITY_CHECK` / `READY` / `CANCELLED`），或在 Phase 2 直接移除该列，改由 `stable_at IS NOT NULL` 与 task 状态派生。
- **对现有设计的影响**：Phase 2 需确认列语义；状态机本身不变。

### 【架构问题】A3：`transfer_task` 缺 `file_id` / `version_no`

- **问题**：§38 的 claim 串行 SQL 使用 `previous.file_id`，但 §34 的 `transfer_task` DDL 无 `file_id`，只能 join `file_version`。
- **风险**：串行守卫每次 claim 都要 join，且缺少 `(file_id, version_no)` 索引时随数据增长变慢。
- **建议方案**：在 `transfer_task` 冗余 `file_id` 与 `version_no`（创建任务时写入，不可变），并建 `(file_id, version_no)` 索引。
- **对现有设计的影响**：Phase 2 DDL 增两列一索引；状态机不变。
