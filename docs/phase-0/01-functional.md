# 01 Functional Requirements（功能需求）

> 本文件是 Phase 0 需求拆解文档的功能需求部分。事实源为 `phase0-baseline.md`（权威决策基线）与整体设计文档。
> 状态集合、状态名与迁移语义严格遵循基线 §1–§3，共 9 个状态，不新增状态。

## 本部分范围

- 用 `FR-xx` 编号描述 NAS → S3 → B2B Gateway → 客户 SFTP 中转系统的 **11 个功能域**的功能需求。
- 每个功能域固定给出 5 个要素：`触发条件` / `输入` / `输出` / `持久化落点` / `幂等性`，并补充 `正常流程` 与 `异常/边界要点`。
- 给出功能域到状态迁移（T 编号）与持久化表（`nas`、`customer_space`、`directory`、`file`、`file_version`、`transfer_task`、`transfer_attempt`、`transfer_event`）的映射矩阵。
- 给出端到端主流程，串起 NAS → Scanner → 稳定检测 → 任务 → S3 → Gateway → `DELIVERED`，并逐步标注状态。
- 标注与已确认架构冲突处为 `【架构问题】`，给出问题 / 风险 / 建议方案 / 对现有设计的影响。

## 本部分不做什么

- **不写代码**：不产出任何 `*.java`、`*.sql`、`pom.xml`、`mvnw`；不实现任何 Service、Repository、Controller。
- **不做详细设计决策**：不决定类结构、线程模型、重试算法实现细节、Spring 配置键的最终命名（仅引用基线中的配置名作为语义锚点）。
- **不定义 DDL**：不给出 `CREATE TABLE` / 索引 / 约束的最终建表语句。文中若出现 SQL 片段，均为“示意，非 DDL 交付物”，仅用于精确表达语义。
- 不引入新状态、不引入 MQ/分布式事务/分布式锁/S3 版本控制/文件内容 hash（基线 §0 明确不引入）。
- 不改动架构。发现冲突只标注 `【架构问题】`，不在本文件内自行修订架构。

## 对应整体设计文档章节

- §2 已确认的业务约束（NAS、目录与客户、文件发现、文件变化）
- §7 文件模型、§8 为什么不计算文件 SHA-256、§9 文件稳定性检测
- §10 Scanner 设计、§11 扫描过程、§12 文件不存在处理、§13 状态机
- §19 同一路径版本串行、§20 同一路径串行的数据库约束、§21 版本最终一致性
- §22 Gateway API、§23 Gateway 调用时序、§24 Gateway 超时、§25 Retry、§26 Exponential Backoff、§27 Retry History
- §28 `transfer_task`、§29 `transfer_attempt`
- §40 S3 Object Key、§41 S3 上传、§42 一个文件默认一个 Worker、§43 并发控制、§44 为什么要限制并发
- §45 Scheduler 与 Worker 解耦、§46 Dispatcher、§47 背压、§48 Gateway Worker、§49 Gateway 失败、§50 不删除失败任务、§51 人工强制重试
- §61 一次完整成功流程、§62 文件修改流程、§63 Gateway 失败流程、§64 JVM Crash 流程、§65 Scanner Crash、§66 数据库事务原则
- §84 系统启动恢复、§85 Graceful Shutdown、§86 故障场景矩阵、§87 最终一致性定义、§88 最核心的设计原则、§89 最终推荐架构、§90 V1 开发优先级、§91 竞态注意点、§92 V1 验收标准
- 基线 §1 状态集合、§2 权威迁移矩阵、§3 supersede / latest wins、§4 数据模型权威要点、§5 S3 Object Key、§6 并发模型、§7 崩溃恢复、§8 数据保留

## 状态集合（与基线 §1 逐字一致，恰好 9 个）

`DISCOVERED` → `STABILITY_CHECK` → `READY` → `UPLOADING` → `S3_UPLOADED` → `GATEWAY_DELIVERING` → `DELIVERED`；`STABILITY_CHECK`/`READY` → `CANCELLED`；失败支路 → `WAITING_RETRY`。

| 状态 | 含义 | 终态 |
|---|---|---|
| `DISCOVERED` | 首次发现该 `file_version`，已落库，尚未进入稳定判定 | 否 |
| `STABILITY_CHECK` | 稳定判定中（已观察一次或多次，等待下一次一致观察） | 否 |
| `READY` | 已稳定，可被 worker claim 执行 | 否 |
| `UPLOADING` | 已 claim，正在从 NAS 流式读取并 PUT 到 S3 | 否 |
| `S3_UPLOADED` | S3 PUT 成功且已提交；等待/进入 Gateway 投递 | 否 |
| `GATEWAY_DELIVERING` | 已 claim，正在调用 B2B Gateway | 否 |
| `WAITING_RETRY` | 本次尝试失败/结果未知，等待 `next_retry_at` 再次执行 | 否 |
| `DELIVERED` | Gateway 明确返回成功（文件已落客户 SFTP） | 是 |
| `CANCELLED` | 主动取消（文件消失 / 目录禁用 / 被新版本 supersede） | 是 |

> 禁止新增 `CLAIMED`/`PROCESSING`/`FAILED`/`SUPERSEDED`/`UPLOADED`/`DELIVERING` 等状态；supersede 复用 `CANCELLED` + `cancel_reason = SUPERSEDED_BY_NEWER_VERSION`。

## 编号索引

| 编号 | 功能域 | 主要迁移（基线 §2） | 对应整体设计 |
|---|---|---|---|
| FR-01 | NAS 扫描（Scanner） | T1、T2、T3 | §10、§11、§12 |
| FR-02 | 文件稳定检测（Stability Check） | T3、T4、T5 | §9、§13 |
| FR-03 | 文件版本（File Version） | T1、T3、T4、T20、T21 | §7、§21、§62 |
| FR-04 | 任务创建（Task Creation） | T1、T4、T6 | §28、§45、§46、§66 |
| FR-05 | S3 上传（S3 Upload） | T6、T8、T9、T10 | §40、§41 |
| FR-06 | Gateway 投递（Gateway Delivery） | T11、T12、T13、T14、T15、T16 | §22、§23、§24、§48、§49 |
| FR-07 | Retry | T9、T14、T15、T16、T17、T18 | §25、§26、§63 |
| FR-08 | Crash Recovery | T10、T12、T16 | §64、§84、§85 |
| FR-09 | Manual Retry | T19、T22 | §51 |
| FR-10 | Audit | 全部迁移（记录） | §27、§29、§8 |
| FR-11 | Monitoring | 只读观察 | §86、§90 |

---

## FR-01 NAS 扫描（Scanner）

| 要素 | 内容 |
|---|---|
| 触发条件 | 每个 `directory` 独立的定时调度（`ScheduledDirectoryScan`）到期触发，扫描周期由 `directory` 配置（几分钟 ~ 24h）；应用启动时 Scanner **最后**启动（§84 启动顺序）。 |
| 输入 | `directory` 配置（`nas_id`、`path`、文件后缀、`scan_interval`、`enabled`、`customer_space_id`、`max_concurrency`）；SMB 目录列表（`relative_path`、`size`、`mtime`）。 |
| 输出 | 新/更新的 `file` 与 `file_version` 观察记录；`DISCOVERED` 状态任务；扫描耗时/文件数指标；交给 Dispatcher 的可执行事实（Scanner 不直接上传）。 |
| 持久化落点 | 读 `nas`、`customer_space`、`directory`；写/更新 `directory`（`last_scan_at`）、`file`（upsert）、`file_version`（insert 观察值）、`transfer_task`（insert，`status = DISCOVERED`）。 |
| 幂等性 | 重复扫描不产生重复记录。`file` 由 `UNIQUE(nas_id, directory_id, relative_path)` 兜底；`file_version` 由 `UNIQUE(file_id, fingerprint)` 兜底；`transfer_task` 由 `UNIQUE(file_version_id)` 兜底；插入采用 `ON CONFLICT DO NOTHING`（§65）。重复执行只会跳过已存在记录或刷新观察值，不会产生第二个任务。 |

**正常流程**

1. 取该 `directory` 的配置，校验 `enabled`。
2. SMB `list` 该目录（单目录最多约 1 万文件，全量扫描 + DB 增量判断可接受，§11）。
3. 按配置的文件后缀过滤。
4. 对每个候选文件取 `relative_path`、`size`、`mtime`。
5. 查询 DB 判断：新文件 / 新版本 / 已稳定 / 已处理。
6. 创建或更新 `file`、`file_version`（首次发现状态为 `DISCOVERED`，随即进入 `STABILITY_CHECK`）。
7. 同事务创建 `transfer_task`（`DISCOVERED`），`s3_object_key = <customer-space>/<relative-path>`（§40）。
8. 更新 `directory.last_scan_at`，把可执行事实交给 Dispatcher（Scanner 与 Worker 解耦，§45）。

**异常/边界要点**

1. **NAS 不可访问**：本次扫描失败，不写入记录，下一周期重试（§86）；不得因单目录失败阻塞其他目录。
2. **目录被禁用**（`enabled = false`）：不再扫描该目录，既有未开始读取的任务按取消 / supersede 处理（T5/T7/T21）。
3. **扫描中 JVM crash**：重启后重新扫描，已写入的 `file_version`/`transfer_task` 由唯一约束 `ON CONFLICT DO NOTHING` 防重复，**不需要 checkpoint** 即可保证正确性（§65）。
4. **文件正在写入**：首次发现一律进 `STABILITY_CHECK`，绝不直接 `READY`（§92）。
5. **大目录内存与 DB 往返**：1 万文件需分批查询/写入，避免一次性把目录内容全部载入 JVM。

---

## FR-02 文件稳定检测（Stability Check）

| 要素 | 内容 |
|---|---|
| 触发条件 | Scanner 已落库某 `file_version` 后，在下一（或多）次扫描再次观察到同一 `path`。 |
| 输入 | 上一次观察的 `size`/`mtime`/`fingerprint`；本次观察的 `size`/`mtime`。 |
| 输出 | 稳定判定结果；`STABILITY_CHECK → READY`（T4）或保持/重置 `STABILITY_CHECK`（T3）；`file_version.stable_at`。 |
| 持久化落点 | 更新 `file_version`（`size`/`mtime`/`fingerprint`/`stable_at`/观察基线）；`transfer_task`（`DISCOVERED → STABILITY_CHECK → READY`）。 |
| 幂等性 | 相邻两次 `path + size + mtime` 完全一致时，向 `READY` 的迁移只发生一次（条件 UPDATE `WHERE id=? AND status=STABILITY_CHECK`，影响行数 0 即放弃）。同内容版本由 `UNIQUE(file_id, fingerprint)` 保证唯一，单任务由 `UNIQUE(file_version_id)` 保证；重复观察仅刷新观察值，不产生新版本或新任务。 |

**正常流程**

1. 首次发现写入 `file_version`，`DISCOVERED → STABILITY_CHECK`（T2）。
2. 下一周期再次观察同一路径的 `size`/`mtime`。
3. `path + size + mtime` 完全一致 → 写 `stable_at = now()`，迁移 `READY`（T4）。
4. 不一致（`size` 或 `mtime` 变化）→ 更新观察值、重置稳定基线，仍为 `STABILITY_CHECK`（T3），不产生新 task。
5. 稳定后由串行守卫决定何时可被 claim（同 `file_id` 无更早非终态版本，§3.2）。

**异常/边界要点**

1. **文件持续写入**：`size`/`mtime` 持续变化 → 永远停留 `STABILITY_CHECK`，不上传（§92 验收标准）。
2. **“恰好相同”的误判**：本方案不做内容 hash（§8），两次一致但内容仍在变的极端情况被接受，属已知取舍。
3. **稳定确认依赖扫描周期**：24h 周期的目录可能需等一个完整周期才能确认稳定（基线 Q1 开放问题：是否引入 `stabilityRecheckInterval`）。
4. **mtime 精度/时区**：影响 `fingerprint` 一致性判定（基线 Q2 开放问题）。
5. **稳定前文件消失 / 目录禁用 / supersede**：`STABILITY_CHECK → CANCELLED`（T5），守卫为“未开始读取 NAS”。

---

## FR-03 文件版本（File Version）

| 要素 | 内容 |
|---|---|
| 触发条件 | 同一逻辑 `file`（`file_id`）被再次观察到，但 `path + size + mtime` 与既有版本不同（`fingerprint` 变化），即产生新版本。 |
| 输入 | `file_id`、新 `size`/`mtime`、`relative_path`；当前最大 `version_no`。 |
| 输出 | 新的 `file_version` 记录（`version_no` 递增，状态随状态机）；对应的新 `transfer_task`；旧版本按 supersede 规则评估。 |
| 持久化落点 | `file_version`（insert 新版本、`version_no`、`fingerprint`、`stable_at`）、`transfer_task`（insert 新任务）、`file`（`updated_at` 刷新）。 |
| 幂等性 | 重复扫描同一新内容不会重复创建版本。`UNIQUE(file_id, fingerprint)` 防同内容重复版本，`UNIQUE(file_id, version_no)` 防版本号冲突；重复执行命中 `ON CONFLICT DO NOTHING`。新版本覆盖旧版本的最终一致语义由 S3 同 Object Key 覆盖（§40/§21）+ supersede 保证。 |

**正常流程**

1. 发现同一 `path` 但 `fingerprint` 变化。
2. 计算 `version_no = max(version_no) + 1`。
3. 插入新 `file_version`，进入 `STABILITY_CHECK`。
4. 对同 `file_id` 下 `version_no` 更小的非终态任务按 supersede 规则评估（§3.3）。
5. 新版本稳定后经串行守卫 claim，S3 覆盖同一 Object（`<customer-space>/<relative-path>`，不含版本号）。

**异常/边界要点**

1. **旧版本仍在执行阶段**（`UPLOADING`/`S3_UPLOADED`/`GATEWAY_DELIVERING`）：**不 supersede**，等其 lease 过期后经崩溃恢复转 `READY`（T10）或 `WAITING_RETRY`（T16），下一轮再评估（§3.3 第 4 步）。
2. **旧版本 `DELIVERED`**：永不 supersede（§3.3 第 5 步）。
3. **版本号并发冲突**：`UNIQUE(file_id, version_no)` 兜底，冲突后重算。
4. **客户可能看不到中间版本**：`v1` 成功、`v2` 失败、`v3` 成功 → 最终 S3/SFTP 均为 `v3`，业务接受（§21）。
5. **【架构问题】A2**：`file_version.version_no` 在整体设计 §39 建议、但 §33 DDL 缺失。

> 【架构问题】A2（详见文末）
> - 问题：`file_version.version_no` 在基线 §4.4 与整体设计 §39 作为版本排序/串行判定依据被建议，但整体设计 §33 DDL 未包含该列。
> - 风险：`latest_stable(file)` 与 supersede 判定（§3.1/§3.3）依赖 `version_no` 比较，缺列将无法实现“latest wins”。
> - 建议方案：在 `file_version` 增加 `version_no` 并加 `UNIQUE(file_id, version_no)`；最终 DDL 由 Phase 1 确认。
> - 对现有设计影响：仅新增列与约束，不改变状态机与迁移矩阵。

---

## FR-04 任务创建（Task Creation）

| 要素 | 内容 |
|---|---|
| 触发条件 | Scanner 首次发现符合后缀且目录 `enabled` 的文件，创建 `file_version` 时即可创建 `DISCOVERED` 任务（T1）；稳定判定通过后进入 `READY`（T4）。 |
| 输入 | `file_version_id`、`directory_id`、`customer_space_id`、`file` 快照（`file_path`/`file_size`/`file_mtime`）、`s3_bucket`/`s3_object_key`、`config_version`。 |
| 输出 | `transfer_task` 记录（`status` 从 `DISCOVERED` 起），进入 DB backlog（`READY`/`WAITING_RETRY`）。 |
| 持久化落点 | `transfer_task`（insert，含快照列；`file`/`file_version`/`transfer_task` 同事务，§66）；`transfer_event`（可选，创建审计）。 |
| 幂等性 | 一个 `file_version` 最多一个 `transfer_task`，由 `UNIQUE(file_version_id)` 兜底；重复创建命中 `ON CONFLICT DO NOTHING`。重复执行不会产生第二个任务，也不会重复投递（基线不变量 I1/I6）。 |

**正常流程**

1. Scanner 判定为“新文件”或“新版本”。
2. 同一事务写 `file`、`file_version`、`transfer_task`（§66 原子化）。
3. 计算并落库 `s3_object_key = <customer-space>/<relative-path>`，**不含** UUID/timestamp/version（§40/§5）。
4. 任务状态随稳定判定推进：`DISCOVERED → STABILITY_CHECK → READY`。
5. 由 Dispatcher 按 `status IN (READY, WAITING_RETRY)` 与 `next_retry_at <= now()` 领取（§46）。

**异常/边界要点**

1. **目录禁用**：不创建可执行任务，或创建后按 `CANCELLED` 处理（T5/T7）。
2. **串行守卫**：同 `file_id` 下存在更早非终态任务时，新任务可保持 `READY` 但不可 claim（§3.2）。
3. **事务失败**：本周期跳过，下周期重扫（T1 失败处理）。
4. **配置变更**：任务保存 `config_version` 快照，旧任务不受新配置影响（§86/§92）。
5. **claim 未命中**：`FOR UPDATE SKIP LOCKED` 未抢到 → 保持 `READY`，下轮再试（T6）。

---

## FR-05 S3 上传（S3 Upload）

| 要素 | 内容 |
|---|---|
| 触发条件 | Worker claim 到 `READY` 任务（T6 → `UPLOADING`）；或 `WAITING_RETRY` 到点且 `s3_uploaded_at IS NULL`（T18 → `UPLOADING`）。 |
| 输入 | NAS 文件（流式读取，不做全量内容 hash）、`s3_bucket`、`s3_object_key`、lease（`worker_id`/`claimed_at`/`lease_until`）。 |
| 输出 | S3 Object（覆盖写）；`transfer_attempt`（`attempt_type = S3_UPLOAD`）；任务迁移 `S3_UPLOADED`（T8）或 `WAITING_RETRY`（T9）。 |
| 持久化落点 | `transfer_task`（`status`、`s3_uploaded_at`、`worker_id`/`claimed_at`/`lease_until`、`retry_count`、`next_retry_at`）、`transfer_attempt`（`attempt_type`、`attempt_no`、`success`/`outcome`、`error_code`、`duration_ms`）。 |
| 幂等性 | 重复上传**覆盖同一 Object Key**（S3 不启版本控制，Object Key 不含 UUID/timestamp/version），因此重传天然幂等（基线 I4）。DB 侧由 `UNIQUE(file_version_id)` 与 `UNIQUE(transfer_task_id, attempt_type, attempt_no)` 兜底。崩溃后重传覆盖同一对象。 |

**正常流程**

1. claim 成功，同一事务写 `worker_id`/`claimed_at`/`lease_until`，`READY → UPLOADING`（T6）。
2. 从 NAS 流式读取文件，`PutObject` 到 `s3_object_key`（单文件 ≤ 10MB，默认普通 `PutObject`，不做 Multipart，§41）。
3. S3 PUT 返回成功。
4. 同一事务写 `transfer_attempt(success)` + `status = S3_UPLOADED` + `s3_uploaded_at`（T8）。
5. 同一次执行继续投递（T11）或下轮 claim 恢复投递（T12）。

**异常/边界要点**

1. **PUT 失败/超时/网络中断/认证失败/5xx** → `WAITING_RETRY`，`retry_count + 1`、计算 `next_retry_at`（T9）。
2. **S3 成功但 DB 提交失败** → 保持 `UPLOADING`，由 lease 恢复后重传覆盖（T10，I4）。
3. **上传中 NAS 删除** → 已打开的句柄继续读取，不中断（§12/§86）。
4. **上传中 JVM crash** → lease 过期 → `READY` → 重传覆盖同一 Object（T10）。
5. **大文件扩展**：未来文件增大到 GB 级时在实现层加 Multipart，**不改变状态机**（§41）。

---

## FR-06 Gateway 投递（Gateway Delivery）

| 要素 | 内容 |
|---|---|
| 触发条件 | 同一次 worker 执行继续投递（T11）或崩溃恢复路径（T12）；或 `WAITING_RETRY` 到点且 `s3_uploaded_at IS NOT NULL`（T17）。 |
| 输入 | `s3_bucket`、`s3_object_key`、`customer_space`；Gateway 认证凭据占位符 `${GATEWAY_APP_ID}` / `${GATEWAY_APP_KEY}`。 |
| 输出 | Gateway 明确成功 → `DELIVERED`（T13）；失败 → `WAITING_RETRY`（T14/T15/T16）；`transfer_attempt`（`attempt_type = GATEWAY_DELIVER`）。 |
| 持久化落点 | `transfer_task`（`status`、`completed_at`、`retry_count`、`next_retry_at`、lease）、`transfer_attempt`（`success`/`outcome`、`http_status`、`gateway_request_id`、`error_code`、`error_message`）。 |
| 幂等性 | 结果未知/超时一律按失败重投，**允许重复投递**（At-least-once，基线 I5）。同一任务的 `attempt_no` 由 `UNIQUE(transfer_task_id, attempt_type, attempt_no)` 防重复记录；同一 `file_version` 单任务由 `UNIQUE(file_version_id)` 保证。重复投递由业务接受（§24）。 |

**正常流程**

1. claim / continue，`S3_UPLOADED → GATEWAY_DELIVERING`（T11/T12/T17）。
2. 创建 `transfer_attempt`（`GATEWAY_DELIVER`）。
3. 调用 `POST /delivery`（§22），请求含 `bucket`/`objectKey`/`customerSpace`。
4. Gateway 从 S3 取对象并上传到客户 SFTP。
5. Gateway **明确**返回成功 → 同一事务写 `attempt(success = true)` + `status = DELIVERED` + `completed_at`（T13）。

**异常/边界要点**

1. **4xx/5xx 明确失败** → `WAITING_RETRY`，`retry_count + 1`、`next_retry_at = backoff(...)`（T14）。
2. **超时 / 连接中断 / 结果未知** → `WAITING_RETRY`，`error_code = UNKNOWN_OUTCOME`，允许重复投递（T15）。
3. **崩溃恢复 lease 过期** → `WAITING_RETRY`，**不猜测**上次是否成功，直接重投（T16，§64）。
4. **Gateway 长期不可用** → 无限 retry，任务永不消失（§50）。
5. **契约未定**：Gateway 认证方式、成功响应契约、幂等键、是否回传 object 标识（基线 Q3 开放问题）。
6. **【架构问题】A1**：Gateway 在途请求与 S3 对象覆盖的竞态无法完全消除，靠宽限期收敛。

> 【架构问题】A1（详见文末）
> - 问题：`GATEWAY_DELIVERING` 的 v1 请求在途时，v2 可能覆盖同一 S3 Object，Gateway 可能读到错误版本。
> - 风险：客户可能先收到新版本内容或收到一次重复投递（At-least-once 固有代价）。
> - 建议方案：严格实现“活跃 lease 期间禁止 supersede” + “宽限期 ≥ Gateway read timeout + lease 时长”（§3.5）；残余风险业务已接受。
> - 对现有设计影响：不改变状态机；仅约束 supersede 时机与宽限期配置。

---

## FR-07 Retry

| 要素 | 内容 |
|---|---|
| 触发条件 | 任一执行阶段失败/超时/结果未知后进入 `WAITING_RETRY`（T9/T14/T15/T16）；`next_retry_at` 到期由 Dispatcher 重新领取（T17/T18）。 |
| 输入 | `retry_count`、上一次失败时间、退避配置（`retry.initialDelay=30s`、`multiplier=2`、`maxDelay=1h`、`jitter=20%`，§26）。 |
| 输出 | `WAITING_RETRY` + `next_retry_at`；到点后回 `UPLOADING`（`s3_uploaded_at IS NULL`）或 `GATEWAY_DELIVERING`（`s3_uploaded_at IS NOT NULL`）。 |
| 持久化落点 | `transfer_task`（`retry_count`、`next_retry_at`、`status`）、`transfer_attempt`（每次尝试记录）、`transfer_event`（可选）。 |
| 幂等性 | Retry 不产生新任务，只更新既有任务的 `status`/`retry_count`/`next_retry_at`；由 `UNIQUE(file_version_id)` 保证单任务、`UNIQUE(transfer_task_id, attempt_type, attempt_no)` 保证 attempt 不重复。重复触发 retry 安全（覆盖写 `next_retry_at`）。 |

**正常流程**

1. 执行失败 → `retry_count + 1`、`next_retry_at = calculateBackoff(...)`、`status = WAITING_RETRY`（T9/T14/T15/T16）。
2. 释放 lease，worker 归还（Retry **不占用 worker**，§25）。
3. Dispatcher 定期查询 `status IN (READY, WAITING_RETRY) AND next_retry_at <= now()`（§46）。
4. `s3_uploaded_at IS NULL` → `UPLOADING`（T18）；否则 → `GATEWAY_DELIVERING`（T17）。
5. 指数退避序列：30s → 1m → 2m → 4m → 8m → 16m → 32m → 1h → 1h …（§26）。

**异常/边界要点**

1. **达到 maxRetry 不删除任务**：`retry_count = 100` 仍为 `WAITING_RETRY`，永远 retry 直到成功（§26/§50，不变量 I3）。
2. **退避上限**：`maxDelay = 1h`，加 20% jitter 防惊群。
3. **长期失败触发 supersede 阈值**：`retry_count >= supersede.afterFailedAttempts`（默认 5）或等待超过 `afterWaiting`（默认 30m），**仅当存在更新稳定版本**时才被 supersede（§3.3）。
4. **分支判断依赖冗余列**：`WAITING_RETRY` 的下一动作依赖 `transfer_task.s3_uploaded_at`，否则只能靠 attempt 历史推断（基线 §4.4）。
5. **Retry 与并发配额**：到点任务仍需通过串行守卫与并发配额才可 claim。

---

## FR-08 Crash Recovery

| 要素 | 内容 |
|---|---|
| 触发条件 | 应用启动时执行 Recovery（§84 启动顺序：DB → Recovery → Retry/Dispatcher + Worker → Scanner）；或周期扫描 stale lease。 |
| 输入 | `transfer_task`（`status`、`lease_until`、`s3_uploaded_at`、`worker_id`）。 |
| 输出 | 过期 lease 任务恢复：`UPLOADING → READY`（T10）；`S3_UPLOADED → GATEWAY_DELIVERING`（T12）；`GATEWAY_DELIVERING → WAITING_RETRY`（T16）。 |
| 持久化落点 | `transfer_task`（`status`、清空 `worker_id`/`lease_until`、依据 `s3_uploaded_at` 判定）；`transfer_event`（恢复审计，可选）。 |
| 幂等性 | 恢复必须条件更新 `WHERE id=? AND status=? AND lease_until < now()`，影响行数 0 即放弃（§7），避免与仍在运行的 worker 抢同一任务；重复恢复安全。`UNIQUE(file_version_id)` 保证不产生额外任务。 |

**正常流程**

1. 启动时连接 PostgreSQL 并校验 S3/Gateway 可达（§84）。
2. 扫描 `lease_until < now()` 的非终态任务。
3. 按 `status` 分派恢复迁移：`UPLOADING`→`READY`（T10）；`S3_UPLOADED`→`GATEWAY_DELIVERING`（T12）；`GATEWAY_DELIVERING`→`WAITING_RETRY`（T16）。
4. 每个恢复动作使用条件更新，避免覆盖活跃 worker。
5. Recovery 完成后再启动 Retry/Dispatcher + Worker，**最后**启动 Scanner，避免新任务与恢复任务同时抢 worker（§84）。

**异常/边界要点**

1. **恢复与在途 worker 竞争**：条件更新保证不会误抢仍在运行的任务。
2. **启动顺序**：Scanner 必须先于 Worker 之后启动；顺序错误会导致 worker 争抢（§84）。
3. **优雅关闭超时强杀**：SIGTERM 流程（停止新扫描 → 停止 claim → 等待在途 worker → 尽量完成当前上传 → 释放 lease → 退出）超时后由 lease 兜底（§85）。
4. **S3 已成功但 DB 未提交**：恢复后重传覆盖同一 Object（I4）。
5. **Gateway 响应丢失**：恢复后重投，允许重复投递（§64）。

---

## FR-09 Manual Retry

| 要素 | 内容 |
|---|---|
| 触发条件 | operator 显式调用 `forceRetry(taskId)`（第一版无前端，仅 Service/DB 层支持，§51）。 |
| 输入 | `task_id`、operator 身份；被操作版本是否为该 `file` 的 `latest_stable`。 |
| 输出 | `WAITING_RETRY → READY`（T19）；或拒绝（非最新稳定版本，`error_code = SUPERSEDED_BY_NEWER_VERSION`）。 |
| 持久化落点 | `transfer_task`（`status`、`next_retry_at = now()`）、`transfer_event`（operator action 审计，`operator = <操作人>`）。 |
| 幂等性 | 重复 manual retry 同一任务安全：仅把 `next_retry_at` 前移，不产生新任务（`UNIQUE(file_version_id)`）；是否生效由 `latest_stable` 判定与串行守卫决定，重复调用结果一致。 |

**正常流程**

1. operator 指定目标任务。
2. 校验该版本仍是该 `file` 的 `latest_stable`（`stable_at IS NOT NULL` 且状态非 `CANCELLED` 的最大 `version_no`）。
3. 是 → `WAITING_RETRY → READY`（T19），`next_retry_at = now()`，写 `transfer_event`。
4. 否 → 拒绝，错误码 `SUPERSEDED_BY_NEWER_VERSION`，并写 `transfer_event` 记录 operator action（§3.6）。
5. 之后由 Dispatcher 正常 claim。

**异常/边界要点**

1. **被 supersede 的 `CANCELLED` 任务不可复活**（终态，T23，§3.6）。
2. **`WAITING_RETRY → READY` 可能触发 S3 重传**：因为 `READY` 会走 `UPLOADING` 而非直接 `GATEWAY_DELIVERING`（基线 Q4 开放问题，是否接受待确认）。
3. **对 `DELIVERED` 任务不可 manual retry**（T22 终态不可复活）。
4. **对活跃 lease 任务不可 manual retry**：需先等待 lease 释放，避免与在途 worker 冲突。
5. **鉴权**：第一版无前端，调用方需自证 operator 身份；前端/API 属 P2（§90）。

---

## FR-10 Audit

| 要素 | 内容 |
|---|---|
| 触发条件 | 每次状态迁移、每次 S3/Gateway attempt、每次 supersede、每次 operator action。 |
| 输入 | 迁移前后状态、`task`/`version` 标识、`operator`（system / 操作人）、时间、`error_code`、`cancel_reason`、`superseded_by_version_no`。 |
| 输出 | `transfer_attempt` 尝试历史、`transfer_event` 事件流、`file_version` 版本链、`transfer_task` 审计冗余列。 |
| 持久化落点 | `transfer_attempt`（每次 S3/Gateway 尝试）、`transfer_event`（状态迁移与 operator action）、`file_version`（`first_seen_at`/`stable_at`/`version_no` 版本链）、`transfer_task`（`cancel_reason`/`superseded_by_version_no`/`cancelled_at`/`last_attempt_finished_at`）。 |
| 幂等性 | `transfer_attempt` 由 `UNIQUE(transfer_task_id, attempt_type, attempt_no)` 防重复记录；`transfer_event` 为追加写，重复执行最多多出一条审计事件，不改变业务状态。审计记录本身不参与业务幂等判定。 |

**正常流程**

1. 所有状态迁移经 `TaskStateMachine.transition(...)` 校验（§2 实现约束）。
2. 业务事务内写 `transfer_attempt`（成功/失败/未知）。
3. 迁移与 supersede/operator action 写 `transfer_event`。
4. 记录 `error_code`、`http_status`、`gateway_request_id`、`duration_ms`，供运维完整追踪（§27/§29）。
5. 业务数据保留 1 年，仅清理终态记录（§8）。

**异常/边界要点**

1. **审计与业务事务的原子性**：若 `transfer_event` 与状态迁移同事务，则事件写入失败会导致一起回滚，需在 Phase 1 明确取舍。
2. **保留策略**：1 年仅清理 `DELIVERED`/`CANCELLED` 且 `created_at < now() - 1 year` 的记录；非终态任务永不清理（§8）。
3. **`transfer_event` 为可选表**（基线 §4.1）：缺失时审计能力降级。
4. **【架构问题】A4**：`transfer_attempt.success BOOLEAN` 无法表达“结果未知”，与“超时按失败处理”冲突。

> 【架构问题】A4（详见文末）
> - 问题：`transfer_attempt.success BOOLEAN` 无法区分 `SUCCESS`/`FAILURE`/`UNKNOWN` 三态。
> - 风险：`GATEWAY_DELIVERING` 结果未知按失败处理（T15），审计上会丢失“未知”语义，影响问题定位与重复投递统计。
> - 建议方案：`transfer_attempt.outcome` 枚举 `SUCCESS`/`FAILURE`/`UNKNOWN`（基线 §4.4）。
> - 对现有设计影响：仅字段语义细化，不改变状态机。

---

## FR-11 Monitoring

| 要素 | 内容 |
|---|---|
| 触发条件 | 周期性采集；每次状态迁移、每次 attempt、每次 claim/lease 续租。 |
| 输入 | `transfer_task` 状态分布与 backlog 深度、`retry_count` 分布、`transfer_attempt` 结果、扫描耗时、worker/lease 指标。 |
| 输出 | 指标（Micrometer/Prometheus 风格）、结构化日志、告警（如 `WAITING_RETRY` 超阈值、lease 恢复频繁）。 |
| 持久化落点 | **只读** `transfer_task`、`transfer_attempt`、`transfer_event`、`file_version`；应用日志**不进** PostgreSQL（§8）；指标不落业务表。 |
| 幂等性 | 监控为只读，不改变业务状态，重复采集安全；无唯一约束需求，只读查询不影响任何业务幂等性。 |

**正常流程**

1. 采集各状态计数（9 个状态）与 backlog 深度。
2. 采集 `WAITING_RETRY` 等待时长、`retry_count` 分布、失败率。
3. 采集 supersede 次数、crash recovery 次数、stale lease 数量。
4. 采集扫描耗时、文件发现速率、上传/投递吞吐。
5. 触发告警阈值（如 backlog 持续增长、长期 `WAITING_RETRY`）。

**异常/边界要点**

1. **无 `FAILED` 终态**：不能靠 `FAILED` 状态告警，必须用 `retry_count` 与 `WAITING_RETRY` 等待时长（基线 §1）。
2. **非终态任务永不清理**：backlog 需持续监控，避免 DB 无限增长（不变量 I7）。
3. **单机部署**：无分布式指标聚合，指标来源为单实例。
4. **Admin 前端不在范围**（基线 §0），监控为 P1 能力（§90）。
5. **指标与告警阈值未定**：需在 `monitoring` 模块配置化，属开放问题。

---

## 功能域 → 状态迁移 → 持久化落点 总览

| 功能域 | 主要状态迁移（T 编号） | 主要持久化落点 |
|---|---|---|
| FR-01 NAS 扫描 | T1（→`DISCOVERED`）、T2（`DISCOVERED`→`STABILITY_CHECK`）、T3 | `nas`、`customer_space`、`directory`、`file`、`file_version`、`transfer_task` |
| FR-02 文件稳定检测 | T3、T4（→`READY`）、T5（→`CANCELLED`） | `file_version`、`transfer_task` |
| FR-03 文件版本 | T1、T3、T4、T20/T21（supersede→`CANCELLED`） | `file`、`file_version`、`transfer_task` |
| FR-04 任务创建 | T1、T4、T6（`READY`→`UPLOADING` claim） | `file`、`file_version`、`transfer_task`、`transfer_event` |
| FR-05 S3 上传 | T6、T8（→`S3_UPLOADED`）、T9（→`WAITING_RETRY`）、T10（→`READY`） | `transfer_task`、`transfer_attempt` |
| FR-06 Gateway 投递 | T11、T12、T13（→`DELIVERED`）、T14/T15/T16（→`WAITING_RETRY`） | `transfer_task`、`transfer_attempt` |
| FR-07 Retry | T9、T14、T15、T16、T17、T18 | `transfer_task`、`transfer_attempt` |
| FR-08 Crash Recovery | T10、T12、T16 | `transfer_task`、`transfer_event` |
| FR-09 Manual Retry | T19（`WAITING_RETRY`→`READY`）、T22（拒绝终态复活） | `transfer_task`、`transfer_event` |
| FR-10 Audit | 全部迁移（记录，不改变迁移本身） | `transfer_attempt`、`transfer_event`、`file_version`、`transfer_task` |
| FR-11 Monitoring | 只读观察全部迁移 | 只读 `transfer_task`、`transfer_attempt`、`transfer_event`、`file_version` |

> 迁移编号与语义严格对应基线 §2 权威迁移矩阵；`T22`/`T23` 为“禁止迁移”，仅用于拒绝语义说明。

## 幂等性 / 唯一约束 速查

| 重复风险 | 兜底机制 | 语义 |
|---|---|---|
| 并发扫描重复创建 `file` | `UNIQUE(nas_id, directory_id, relative_path)` | 重复插入被拒 / `ON CONFLICT DO NOTHING` |
| 并发扫描重复创建 `file_version` | `UNIQUE(file_id, fingerprint)` | 同内容版本唯一 |
| 版本号冲突 | `UNIQUE(file_id, version_no)` | 冲突后重算 |
| 同一版本多个任务 | `UNIQUE(file_version_id)` | 一版本一任务 |
| 重复 attempt 记录 | `UNIQUE(transfer_task_id, attempt_type, attempt_no)` | 尝试历史唯一 |
| S3 重传 | Object Key 固定（`<customer-space>/<relative-path>`），S3 不启版本控制 | 覆盖写，天然幂等 |
| Gateway 重投 | At-least-once + 业务接受重复 | 允许重复投递 |
| Scanner 崩溃重扫 | DB 唯一约束 + `ON CONFLICT DO NOTHING` | 无需 checkpoint |

---

## 端到端主流程

以下编号步骤串起 NAS → Scanner → 稳定检测 → 任务 → S3 → Gateway → `DELIVERED`，并在每步标注状态。

1. **NAS 上出现稳定写入的文件**（例：`/mnt/nas01/customer-a/order/A.zip`）——状态：无（NAS 侧）。
2. **Scanner 定时扫描目录**（FR-01），SMB `list` + 后缀过滤 + 取 `path/size/mtime`——状态：无 → 首次发现。
3. **落库 `file` 与 `file_version`，创建 `transfer_task`**（FR-04）——状态：`DISCOVERED`（T1）。
4. **进入稳定判定**（FR-02）——状态：`DISCOVERED → STABILITY_CHECK`（T2）。
5. **下一次扫描观察一致**（`path + size + mtime` 完全相同）——状态：`STABILITY_CHECK → READY`（T4）；若变化则回 T3 重置观察基线。
6. **Dispatcher 按串行守卫与并发配额 claim**（FR-04/FR-07）——状态：`READY → UPLOADING`（T6）。
7. **流式读取 NAS 文件并 `PutObject` 到 S3**（FR-05，Object Key = `<customer-space>/<relative-path>`）——状态：`UPLOADING`。
8. **S3 PUT 成功且 DB 提交**（FR-05）——状态：`UPLOADING → S3_UPLOADED`（T8），写 `s3_uploaded_at`。
9. **进入 Gateway 投递**（FR-06，同次执行 T11 或恢复路径 T12）——状态：`S3_UPLOADED → GATEWAY_DELIVERING`。
10. **调用 `POST /delivery`**，Gateway 从 S3 取对象并 SFTP 上传到客户（§22/§23）——状态：`GATEWAY_DELIVERING`。
11. **Gateway 明确返回成功**——状态：`GATEWAY_DELIVERING → DELIVERED`（T13），同一事务写 `attempt(success)` + `completed_at`。
12. **结束**——状态：`DELIVERED`（终态，不可复活，T22）。

失败支路（贯穿第 7–11 步）：

- S3 失败 → `UPLOADING → WAITING_RETRY`（T9）；到点且 `s3_uploaded_at IS NULL` → 回 `UPLOADING`（T18）。
- Gateway 失败/超时/结果未知/崩溃恢复 → `GATEWAY_DELIVERING → WAITING_RETRY`（T14/T15/T16）；到点且 `s3_uploaded_at IS NOT NULL` → 回 `GATEWAY_DELIVERING`（T17）。
- 文件在读取前消失/目录禁用/supersede → `STABILITY_CHECK`/`READY` → `CANCELLED`（T5/T7/T21）。
- `WAITING_RETRY` 被更新稳定版本 supersede → `CANCELLED`（T20）。

### 端到端状态迁移序列表（成功路径）

| 步骤 | From | To | T 编号 | 说明 |
|---|---|---|---|---|
| 3 | （无） | `DISCOVERED` | T1 | Scanner 首次发现并落库 `file_version` + 任务 |
| 4 | `DISCOVERED` | `STABILITY_CHECK` | T2 | 首次观察记录完成 |
| 5 | `STABILITY_CHECK` | `READY` | T4 | 相邻两次 `path + size + mtime` 一致 |
| 6 | `READY` | `UPLOADING` | T6 | Worker claim + 写 lease |
| 8 | `UPLOADING` | `S3_UPLOADED` | T8 | S3 PUT 成功且 DB 提交 |
| 9 | `S3_UPLOADED` | `GATEWAY_DELIVERING` | T11 | 同次执行继续投递 |
| 11 | `GATEWAY_DELIVERING` | `DELIVERED` | T13 | Gateway 明确返回成功 |

> 失败/恢复路径不改变上述主链，仅插入 `WAITING_RETRY`（T9/T14/T15/T16）与恢复迁移（T10/T12/T16），再经 T17/T18 回到执行阶段。

---

## 架构问题汇总（详见 `13-open-questions-risks.md`）

| 编号 | 涉及功能域 | 摘要 | 类型 |
|---|---|---|---|
| A1 | FR-06 | Gateway 在途请求与 S3 对象覆盖的竞态无法完全消除，靠“活跃 lease 禁止 supersede + 宽限期”收敛 | 【架构问题】 |
| A2 | FR-03 | `file_version.version_no` 在 §39 建议但 §33 DDL 缺失，而 supersede/latest wins 依赖版本排序 | 【架构问题】 |
| A3 | FR-04/FR-07/FR-08 | `transfer_task` 缺 `file_id`/`version_no`，但 §38 claim SQL 依赖 `previous.file_id` | 【架构问题】 |
| A4 | FR-10 | `transfer_attempt.success BOOLEAN` 无法表达“结果未知”，与超时按失败处理冲突 | 【架构问题】 |
| A5 | FR-02/FR-03/FR-04 | `file_version.status` 与 `transfer_task.status` 双状态并存，事实来源不清 | 【架构问题】 |
| A6 | FR-01/FR-04 | `directory.path` 与 `relative path` 的关系未定义（`mount_path + path` 拼接规则） | 【架构问题】 |
| A7 | FR-10 | 1 年保留策略与“失败任务永不删除”冲突 | 【架构问题】 |
| A8 | FR-01 | 未来多实例下扫描调度缺 DB 级互斥（V1 只留扩展点） | 【架构问题】（低） |

> 开放问题（非架构冲突，但影响功能需求实现）：Q1 扫描周期与稳定确认、Q2 mtime 精度/时区、Q3 Gateway 契约与幂等键、Q4 manual retry 是否触发 S3 重传、Q5 客户 SFTP 文件名/目录规则、Q6 上传后是否校验 size/ETag。
