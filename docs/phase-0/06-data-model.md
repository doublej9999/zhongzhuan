# 06 Data Model（数据模型）

## 本部分范围

定义系统的**权威数据模型**：实体、字段、主键、外键、唯一约束、索引、系统不变量，以及"数据库唯一约束如何成为重复数据的最终防线"。

- 必需表 **7 张**：`nas`、`customer_space`、`directory`、`file`、`file_version`、`transfer_task`、`transfer_attempt`。
- 可选表 **1 张**：`transfer_event`（状态迁移与人工操作审计）。
- 本部分只定义**模型**，不产出 DDL/migration 文件（Phase 2 实现）。

## 本部分不做什么

- 不创建 `*.sql` 文件，不写 Flyway/Liquibase migration。
- 不定义实体类与 Repository 接口。
- 不定义状态迁移规则（见 `04-state-machine.md`）。
- 不定义配置项（见 `07-configuration-model.md`）。

## 对应整体设计文档章节

§7（文件模型）、§27–§35（attempt / task / 表结构）、§36–§39（任务创建事务边界、唯一约束、version_no）、§52（event 审计）、§54（保留）、§69（不变量）、§81–§83（容量与索引）。

---

## 1. 实体关系

```text
nas ──────1:N──────▶ directory ◀──────N:1────── customer_space
                          │
                          │ 1:N
                          ▼
                        file
                          │
                          │ 1:N
                          ▼
                    file_version
                          │
                          │ 1:1
                          ▼
                   transfer_task ◀─────── (worker_id / lease_until：运行时所有权，非业务状态)
                       │       │
                       │       └────1:N──────▶ transfer_event（可选，审计）
                       │
                       │ 1:N
                       ▼
                transfer_attempt
```

| 关系 | 基数 | 强制方式 |
|---|---|---|
| `customer_space` → `directory` | 1:N | `directory.customer_space_id NOT NULL FK` |
| `nas` → `directory` | 1:N | `directory.nas_id NOT NULL FK` |
| `directory` → `file` | 1:N | `file.directory_id NOT NULL FK` |
| `file` → `file_version` | 1:N | `file_version.file_id NOT NULL FK` |
| `file_version` → `transfer_task` | **1:1** | `transfer_task.file_version_id NOT NULL UNIQUE FK` |
| `transfer_task` → `transfer_attempt` | 1:N | `transfer_attempt.transfer_task_id NOT NULL FK` |
| `transfer_task` → `transfer_event` | 1:N | `transfer_event.transfer_task_id NOT NULL FK`（可选表） |

---

## 2. 表定义

> 下列"类型"以 PostgreSQL 语义描述；约束标注为 `PK` / `FK` / `UQ` / `NN` / `CK` / `IDX`。示意性 SQL 仅用于精确表达约束语义，**不是 DDL 交付物**。

### 2.1 `nas`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | BIGSERIAL | PK | |
| `name` | VARCHAR(128) | NN | NAS 名称（如 `nas01`） |
| `mount_path` | VARCHAR(1024) | NN | 操作系统挂载点（如 `/mnt/nas01`） |
| `enabled` | BOOLEAN | NN, 默认 true | |
| `created_at` | TIMESTAMPTZ | NN | |
| `updated_at` | TIMESTAMPTZ | NN | |

- UNIQUE：`name`
- INDEX：`(enabled)`

### 2.2 `customer_space`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | BIGSERIAL | PK | |
| `code` | VARCHAR(128) | NN, UQ | 用于 S3 Object Key 前缀（如 `customer-a`） |
| `name` | VARCHAR(255) | NN | |
| `enabled` | BOOLEAN | NN, 默认 true | |
| `created_at` / `updated_at` | TIMESTAMPTZ | NN | |

- UNIQUE：`code`
- **注意**：`code` 参与 S3 Object Key，一旦有任务产生**不可变更**（否则 Key 漂移）。

### 2.3 `directory`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | BIGSERIAL | PK | |
| `nas_id` | BIGINT | NN, FK→nas(id) | |
| `customer_space_id` | BIGINT | NN, FK→customer_space(id) | **一个目录只对应一个客户空间**（BR-01） |
| `code` | VARCHAR(128) | NN, UQ | 配置中的 directory id（如 `customer-a-order`） |
| `path` | VARCHAR(1024) | NN | 相对 NAS `mount_path` 的路径（拼接规则见【架构问题】A6） |
| `extensions` | JSONB | NN | 允许的后缀数组，如 `[".zip",".xml"]` |
| `scan_interval_sec` | BIGINT | NN, CK > 0 | 扫描周期 |
| `max_concurrency` | INTEGER | NN, CK ≥ 1 | 目录级并发上限 |
| `enabled` | BOOLEAN | NN, 默认 true | |
| `last_scan_at` | TIMESTAMPTZ | NULL | 最近一次成功完成列目录的扫描时刻（Scanner 写，供扫描调度/审计参考） |
| `created_at` / `updated_at` | TIMESTAMPTZ | NN | |

- UNIQUE：`(nas_id, path)`、`code`
- INDEX：`(enabled, nas_id)`
- FK 语义：`customer_space_id` 非空即保证"一目录一客户"

### 2.4 `file`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | BIGSERIAL | PK | |
| `nas_id` | BIGINT | NN | 冗余自 directory，便于唯一约束与查询 |
| `directory_id` | BIGINT | NN, FK→directory(id) | |
| `relative_path` | VARCHAR(2048) | NN | 相对 directory 的路径（如 `2026/09/A.zip`） |
| `created_at` / `updated_at` | TIMESTAMPTZ | NN | |

- **UNIQUE：`(nas_id, directory_id, relative_path)`** ← 逻辑文件身份（BR-08）
- INDEX：`(directory_id, relative_path)`

### 2.5 `file_version`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | BIGSERIAL | PK | |
| `file_id` | BIGINT | NN, FK→file(id) | |
| `version_no` | INTEGER | NN | 同 `file_id` 内单调递增（【架构问题】A2：需确认采纳） |
| `size_bytes` | BIGINT | NN, CK ≥ 0 | |
| `mtime` | TIMESTAMPTZ | NN | 需统一精度与语义（见开放问题 Q2） |
| `fingerprint` | VARCHAR(128) | NN | `SHA-256(relative_path + size + mtime)`，**非内容 hash** |
| `first_seen_at` | TIMESTAMPTZ | NN | |
| `stable_at` | TIMESTAMPTZ | NULL | 稳定确认时间；`NOT NULL` 表示已稳定 |
| `status` | VARCHAR(32) | NN, CK ∈ 状态枚举子集 | 见【架构问题】A5：建议仅表达发现/稳定语义 |
| `created_at` / `updated_at` | TIMESTAMPTZ | NN | |

- **UNIQUE：`(file_id, fingerprint)`** ← 防重复版本（BR-19）
- **UNIQUE：`(file_id, version_no)`** ← 版本有序（BR-20）
- INDEX：`(file_id, version_no)`、`(status, stable_at)`

### 2.6 `transfer_task`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | BIGSERIAL | PK | |
| `file_version_id` | BIGINT | NN, **UQ**, FK→file_version(id) | **一个版本最多一个任务**（BR-23） |
| `file_id` | BIGINT | NN, FK→file(id) | **建议冗余**，串行守卫需要（【架构问题】A3） |
| `version_no` | INTEGER | NN | **建议冗余**，与 file_id 一起支撑 `(file_id, version_no)` 索引 |
| `directory_id` | BIGINT | NN, FK→directory(id) | 快照 |
| `customer_space_id` | BIGINT | NN, FK→customer_space(id) | 快照 |
| `file_path` | VARCHAR(2048) | NN | 快照（绝对路径或相对路径，见 A6） |
| `file_size_bytes` | BIGINT | NN | 快照 |
| `file_mtime` | TIMESTAMPTZ | NN | 快照 |
| `s3_bucket` | VARCHAR(255) | NN | 快照 |
| `s3_object_key` | VARCHAR(2048) | NN | 快照，`<customer-space>/<relative-path>` |
| `s3_uploaded_at` | TIMESTAMPTZ | NULL | **派生列**：决定 `WAITING_RETRY` 下一动作（`04-` 第 5 节） |
| `gateway_route` | VARCHAR(255) | NN | 快照（目标客户空间路由） |
| `config_version` | VARCHAR(64) | NN | 配置快照版本（BR-26） |
| `status` | VARCHAR(32) | NN, CK ∈ 9 状态 | **唯一权威任务状态** |
| `priority` | INTEGER | NN, 默认 0 | claim 排序 |
| `retry_count` | INTEGER | NN, 默认 0 | 单调递增 |
| `next_retry_at` | TIMESTAMPTZ | NULL | claim 条件 |
| `worker_id` | VARCHAR(128) | NULL | 运行时所有权 |
| `claimed_at` | TIMESTAMPTZ | NULL | |
| `lease_until` | TIMESTAMPTZ | NULL | 恢复判定 |
| `last_attempt_finished_at` | TIMESTAMPTZ | NULL | supersede 宽限期判定 |
| `cancel_reason` | VARCHAR(64) | NULL | 如 `FILE_DISAPPEARED` / `DIRECTORY_DISABLED` / `SUPERSEDED_BY_NEWER_VERSION` |
| `superseded_by_version_no` | INTEGER | NULL | supersede 审计 |
| `cancelled_at` | TIMESTAMPTZ | NULL | |
| `started_at` | TIMESTAMPTZ | NULL | |
| `completed_at` | TIMESTAMPTZ | NULL | |
| `created_at` / `updated_at` | TIMESTAMPTZ | NN | |

- **UNIQUE：`file_version_id`** ← 防重复任务（BR-23）
- INDEX：
  - `(status, next_retry_at, created_at)` ← claim / backlog
  - `(status, lease_until)` ← stale lease 恢复
  - `(file_id, version_no)` ← 串行守卫与 supersede（依赖冗余列）
  - `(customer_space_id, status)` ← 客户维度运维查询（可选）
- CK：`status IN (9 状态)`；`retry_count >= 0`

### 2.7 `transfer_attempt`

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | BIGSERIAL | PK | |
| `transfer_task_id` | BIGINT | NN, FK→transfer_task(id) | |
| `attempt_type` | VARCHAR(32) | NN, CK ∈ {`S3_UPLOAD`,`GATEWAY_DELIVER`} | |
| `attempt_no` | INTEGER | NN | 同一 task 同一类型内递增 |
| `started_at` | TIMESTAMPTZ | NN | |
| `finished_at` | TIMESTAMPTZ | NULL | |
| `success` | BOOLEAN | NN | |
| `outcome` | VARCHAR(16) | NN, 建议 | `SUCCESS` / `FAILURE` / `UNKNOWN`（【架构问题】A4） |
| `http_status` | INTEGER | NULL | Gateway/S3 HTTP 状态 |
| `error_code` | VARCHAR(128) | NULL | 如 `UNKNOWN_OUTCOME`、`S3_TIMEOUT` |
| `error_message` | TEXT | NULL | 脱敏后写入 |
| `request_id` | VARCHAR(128) | NULL | 本次调用的关联 ID |
| `gateway_request_id` | VARCHAR(128) | NULL | Gateway 回传 ID |
| `duration_ms` | BIGINT | NULL | |
| `created_at` | TIMESTAMPTZ | NN | |

- **UNIQUE：`(transfer_task_id, attempt_type, attempt_no)`** ← 防重复 attempt
- INDEX：`(transfer_task_id, attempt_no)`、`(attempt_type, success, created_at)`

### 2.8 `transfer_event`（可选，审计）

| 字段 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | BIGSERIAL | PK | |
| `transfer_task_id` | BIGINT | NN, FK→transfer_task(id) | |
| `from_status` | VARCHAR(32) | NULL | 初始事件为 NULL |
| `to_status` | VARCHAR(32) | NN | |
| `reason` | VARCHAR(255) | NULL | |
| `operator` | VARCHAR(128) | NN | `system` 或操作人 |
| `detail` | JSONB | NULL | 附加信息（脱敏） |
| `created_at` | TIMESTAMPTZ | NN | |

- INDEX：`(transfer_task_id, created_at)`、`(to_status, created_at)`

---

## 3. 重复风险的最终防线（逐条）

| # | 重复风险 | 场景 | 数据库最终防线 | 失败时的行为 |
|---|---|---|---|---|
| R1 | **并发扫描** | 两个 Scanner 线程/未来两实例同时发现同一文件 | `UNIQUE(nas_id, directory_id, relative_path)` on `file`；`UNIQUE(file_id, fingerprint)` on `file_version` | `INSERT ... ON CONFLICT DO NOTHING`，只有一个插入成功，另一个静默跳过 |
| R2 | **重复上传** | 崩溃恢复后重新上传同一对象 | 无唯一约束可依赖（S3 覆盖语义）→ 由 `s3_object_key` 稳定命名 + 覆盖实现幂等 | 覆盖同一 Key，结果等价 |
| R3 | **重复 Gateway 调用** | 超时/崩溃后重投 | 无唯一约束 → 由 At-least-once 语义接受；`UNIQUE(transfer_task_id, attempt_type, attempt_no)` 保证 attempt 记录不重复 | 客户可能收到重复文件（业务接受） |
| R4 | **进程崩溃** | commit 前/后崩溃 | 由 `status` 条件更新 + `lease_until` 恢复保证状态不跳跃；`UNIQUE(file_version_id)` 保证不会因重扫而新建任务 | 恢复后重执行（幂等/覆盖） |
| R5 | **重复投递** | Gateway 成功但响应丢失 | 无唯一约束 → 业务接受；attempt 记录区分 `UNKNOWN` 结果 | 重投，客户可能重复收到 |
| R6 | **多 worker 抢同一任务** | 两个 worker 同时 claim | `FOR UPDATE SKIP LOCKED` + 条件更新（`WHERE status=:from AND lease_until < now()`） | 影响行数 0 → 放弃，任务归另一个 worker |
| R7 | 重复 attempt 记录 | 重试计数并发写 | `UNIQUE(transfer_task_id, attempt_type, attempt_no)` | 冲突 → 重新分配 attempt_no 或放弃本次记录 |
| R8 | 重复版本号 | 并发扫描分配 `version_no` | `UNIQUE(file_id, version_no)` | 冲突 → 重读 `max(version_no)` 重试 |

> **原则**：凡是"同一业务事实只能有一条"的约束，必须落成数据库唯一约束；Java 层判断只是优化，不是保证。

### 3.1 示意约束（语义表达，非 DDL 交付物）

```text
file                 UNIQUE (nas_id, directory_id, relative_path)
file_version         UNIQUE (file_id, fingerprint)
file_version         UNIQUE (file_id, version_no)
transfer_task        UNIQUE (file_version_id)
transfer_attempt     UNIQUE (transfer_task_id, attempt_type, attempt_no)
directory            UNIQUE (nas_id, path)
customer_space       UNIQUE (code)
nas                  UNIQUE (name)
```

---

## 4. 系统不变量

| # | 不变量 | 保证机制 |
|---|---|---|
| I1 | 一个 `file_version` 最多一个 `transfer_task` | `UNIQUE(transfer_task.file_version_id)` |
| I2 | 同一 `file_id` 同一时刻最多一个版本处于执行阶段 | 串行守卫（`04-` 6.2）+ `(file_id, version_no)` 索引 |
| I3 | 失败任务永不自动删除；无 `FAILED` 终态 | 无删除路径；状态机（`04-` 第 3 节） |
| I4 | S3 上传成功但 DB 状态未知 → 重启后允许重传 | `s3_uploaded_at` + 稳定 Key 覆盖 |
| I5 | Gateway 结果未知 → 重试，允许重复投递 | `outcome=UNKNOWN` + T15 |
| I6 | Scanner 重复运行不产生重复 task | 唯一约束（R1、R8） |
| I7 | 非终态任务永不进入数据保留清理 | 清理条件含 `status IN ('DELIVERED','CANCELLED')` |
| I8 | `DELIVERED` / `CANCELLED` 为终态，不可复活 | 状态机 |
| I9 | `status` 只能取 9 个枚举值 | 列级 CHECK / 枚举类型 |
| I10 | 任务的 `s3_object_key` 不随版本变化 | Key 生成规则（BR-35）+ 快照列不可变 |

---

## 5. 索引策略

| 表 | 索引 | 服务的查询 | 必要性 |
|---|---|---|---|
| `transfer_task` | `(status, next_retry_at, created_at)` | claim / backlog / 调度 | 必需 |
| `transfer_task` | `(status, lease_until)` | stale lease 恢复 | 必需 |
| `transfer_task` | `(file_id, version_no)` | 串行守卫 / supersede 判定 | 必需（依赖冗余列） |
| `transfer_task` | `(customer_space_id, status)` | 客户维度运维查询 | 可选 |
| `transfer_attempt` | `(transfer_task_id, attempt_no)` | attempt 历史 | 必需 |
| `transfer_attempt` | `(attempt_type, success, created_at)` | 失败率指标 | 可选 |
| `file_version` | `(file_id, version_no)` | 版本链查询 | 必需 |
| `file_version` | `(status, stable_at)` | 稳定文件统计 | 可选 |
| `file` | `(directory_id, relative_path)` | 扫描比对 | 必需 |
| `transfer_event` | `(transfer_task_id, created_at)` | 迁移历史 | 必需（若启用） |

**不过度索引**：不为 `error_message`、`file_path`、`customer_space` 等宽字段或低选择性字段建索引（§83）。容量估算（§81）：365k 版本/年量级下 PostgreSQL 压力可忽略，真正的瓶颈在 NAS IO / S3 / Gateway 并发。

---

## 6. 保留与清理

| 项 | 规则 |
|---|---|
| 保留期 | 业务数据 1 年（BR-54） |
| 清理对象 | **仅终态**（`DELIVERED` / `CANCELLED`）且 `created_at < now() - 1 year`（BR-55） |
| 永不清理 | `DISCOVERED` / `STABILITY_CHECK` / `READY` / `UPLOADING` / `S3_UPLOADED` / `GATEWAY_DELIVERING` / `WAITING_RETRY` |
| 删除顺序 | `transfer_event` → `transfer_attempt` → `transfer_task` → `file_version` → `file`（BR-56） |
| 日志 | 应用日志不入库（BR-57） |

> **冲突裁决**："失败任务永不删除"优先于"保留 1 年"。见【架构问题】A7。

---

## 7. 事务边界

| 操作 | 事务范围 | 事务外 |
|---|---|---|
| 扫描落库（发现/稳定） | `file` + `file_version`（+ `transfer_task` 创建）同一事务，全部 `ON CONFLICT DO NOTHING` | 目录遍历（SMB list/stat） |
| 任务 claim | `SELECT ... FOR UPDATE SKIP LOCKED` + `UPDATE status/worker_id/claimed_at/lease_until` 同一事务 | 无 |
| S3 上传成功 | `UPDATE status=S3_UPLOADED, s3_uploaded_at=now()` + `INSERT transfer_attempt(success)` + `INSERT transfer_event` 同一事务 | **S3 PUT 本身** |
| Gateway 成功 | `UPDATE status=DELIVERED, completed_at` + `INSERT transfer_attempt(success)` + `INSERT transfer_event` 同一事务 | **Gateway HTTP 调用** |
| Gateway 失败 | `UPDATE status=WAITING_RETRY, retry_count+1, next_retry_at, last_attempt_finished_at` + `INSERT attempt` + `INSERT event` 同一事务 | 同上 |
| supersede | `UPDATE status=CANCELLED, cancel_reason, superseded_by_version_no, cancelled_at` + `INSERT transfer_event` 同一事务 | 无 |

> 外部 IO **绝不**放在事务内（`04-` 第 3 节约束 4），避免长事务与连接池耗尽。

---

## 8. 数据模型测试策略（Phase 2 展开）

| 测试 | 内容 |
|---|---|
| Repository 集成测试 | 每个表 CRUD + 约束生效 |
| 唯一约束测试 | 每条 UNIQUE 各一个"重复插入被拒绝"用例 |
| FK 约束测试 | 删除父记录时被拒绝（或按保留策略顺序删除） |
| 并发插入测试 | 两线程同时插入同一 `file`/`file_version`，断言只有一条 |
| claim 并发测试 | 两线程同时 claim，断言任务只被一方持有 |
| 索引有效性 | `EXPLAIN` 断言 claim 与 stale lease 查询走索引 |
| 保留清理测试 | 终态超期被清、非终态不被清、FK 顺序正确 |

---

## 9. 【架构问题】

### 【架构问题】A3：`transfer_task` 缺 `file_id` / `version_no`

- **问题**：§38 claim 串行 SQL 依赖 `previous.file_id`，§34 DDL 无该列。
- **风险**：串行守卫需 join `file_version`，且缺复合索引时随数据增长变慢；supersede 判定同样受影响。
- **建议方案**：`transfer_task` 冗余 `file_id`、`version_no`（创建时写入、不可变），建 `(file_id, version_no)` 索引。
- **影响**：Phase 2 增两列一索引；状态机与业务规则不变。

### 【架构问题】A2：`file_version.version_no` 在 §39 建议但 §33 DDL 缺失

- **问题**：串行与 supersede 依赖版本有序性。
- **风险**：用 `id` 隐含排序语义脆弱。
- **建议方案**：增加 `version_no` + `UNIQUE(file_id, version_no)`，Scanner 在同一事务内分配 `max+1`。
- **影响**：Phase 2 增一列一约束。

### 【架构问题】A4：`transfer_attempt.success BOOLEAN` 无法表达"结果未知"

- **问题**：Gateway 超时/响应丢失按失败处理（T15），但 `success=false` 无法区分"明确失败"与"结果未知"，运维无法判断重复投递风险。
- **风险**：误判投递状态；无法统计未知结果比例。
- **建议方案**：增加 `outcome VARCHAR(16)`（`SUCCESS`/`FAILURE`/`UNKNOWN`），保留 `success` 以兼容。
- **影响**：Phase 2 增一列；状态机不变。

### 【架构问题】A5：`file_version.status` 与 `transfer_task.status` 双状态

- **问题**：两处 `status` 取值同源，事实来源不清。
- **风险**：状态矛盾，运维查询不可信。
- **建议方案**：`transfer_task.status` 为唯一权威；`file_version.status` 仅表达发现/稳定，或移除改由 `stable_at` 派生。
- **影响**：Phase 2 确认列语义。

### 【架构问题】A6：`directory.path` 与 `relative_path` 的拼接规则未定义

- **问题**：需求要求"NAS mount path + relative path"，§31 DDL 只有 `directory.path`，§32 有 `file.relative_path`。
- **风险**：绝对路径拼接、S3 Key 推导、去重键可能不一致。
- **建议方案**：明确 `绝对路径 = nas.mount_path + directory.path + file.relative_path`；S3 Key = `customer_space.code + '/' + directory.path + '/' + file.relative_path`；统一用 `/` 分隔并规范化。
- **影响**：Phase 1/2 的路径工具类与配置校验；数据模型字段含义澄清。

### 【架构问题】A7：1 年保留策略与"失败任务永不删除"冲突

- **问题**：§54 说数据保留 1 年后清理，§50 说失败任务永不删除；若清理包含 `WAITING_RETRY` 则直接丢文件。
- **风险**：**最高**——可能删除仍在重试的任务。
- **建议方案**：清理条件强制 `status IN ('DELIVERED','CANCELLED') AND created_at < now() - 1 year`；非终态永不清理。
- **影响**：保留策略实现（BR-55）；数据模型不变。
