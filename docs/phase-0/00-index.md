# 00 Index（Phase 0 需求拆解文档索引）

## 本部分范围

本文件是 Phase 0 交付物集的**入口与导航**，提供：

1. **交付清单**：14 个文件及其主题、行数规模、与提示词第十九节 13 个部分的对应关系。
2. **阅读顺序**：按角色（业务/架构/开发/运维/测试）给出的推荐阅读路径。
3. **Phase 0 决策 → 《NAS → S3 → B2B Gateway → SFTP 中转系统整体技术设计.md》章节映射表**。
4. **权威事实源与一致性规则**：文档之间冲突时以谁为准。
5. **状态集合与关键阈值速查**。
6. **术语表**与**边界声明**。

## 本部分不做什么

- **不重复正文**：每个主题的完整内容在其对应文件中，本文件只做索引与映射，不复制结论细节。
- **不修改已确认架构**：状态集合保持 9 个；不引入 MQ / Redis / Kubernetes / 分布式事务 / 分布式锁 / S3 对象版本控制 / 内容 hash / Multipart 全套 / Admin 前端。
- **不写代码、不写 DDL、不写 `pom.xml`、不进入 Phase 1**。
- **不写真实密钥**：所有凭据一律占位符（如 `${S3_SECRET_KEY}`）。

## 对应整体设计文档章节

本文件本身是导航页，其映射范围覆盖整体设计文档全部 93 个章节；具体逐项映射见下文 §3。

---

## 1. 交付清单（14 个文件）

| # | 文件 | 主题 | 对应提示词第十九节 |
|---|---|---|---|
| 0 | [`00-index.md`](00-index.md) | 索引、阅读顺序、章节映射 | （本索引，非 13 部分之一） |
| 1 | [`01-functional.md`](01-functional.md) | Functional Requirements（11 个功能域） | 第一部分 |
| 2 | [`02-nfr.md`](02-nfr.md) | Non-Functional Requirements（8 个维度） | 第二部分 |
| 3 | [`03-business-rules.md`](03-business-rules.md) | Business Rules（BR-01–BR-65） | 第三部分 |
| 4 | [`04-state-machine.md`](04-state-machine.md) | State Machine（9 状态 + T1–T23） | 第四部分 |
| 5 | [`05-exception-matrix.md`](05-exception-matrix.md) | Exception Matrix（9 类 / 60 场景） | 第五部分 |
| 6 | [`06-data-model.md`](06-data-model.md) | Data Model（7 必需表 + `transfer_event`） | 第六部分 |
| 7 | [`07-configuration-model.md`](07-configuration-model.md) | Configuration Model（application.yml 配置树） | 第七部分 |
| 8 | [`08-concurrency-model.md`](08-concurrency-model.md) | Concurrency Model（claim / lease / 背压） | 第八部分 |
| 9 | [`09-crash-recovery-model.md`](09-crash-recovery-model.md) | Crash Recovery Model（4 崩溃点 + 启动顺序） | 第九部分 |
| 10 | [`10-security-model.md`](10-security-model.md) | Security Model（密钥/脱敏/最小权限/轮换） | 第十部分 |
| 11 | [`11-observability-model.md`](11-observability-model.md) | Observability Model（指标/日志/告警/查询配方） | 第十一部分 |
| 12 | [`12-acceptance-criteria.md`](12-acceptance-criteria.md) | Acceptance Criteria（11 模块 + 5 维度） | 第十二部分 |
| 13 | [`13-open-questions-risks.md`](13-open-questions-risks.md) | Open Questions / Risks（Q1–Q10、R-01–R-17、A1–A8） | 第十三部分 |

> 每份文档开头均固定三节：`## 本部分范围`、`## 本部分不做什么`、`## 对应整体设计文档章节`，可独立阅读。

## 2. 阅读顺序

### 2.1 通用顺序（首次通读）

```text
00-index → 04-state-machine → 03-business-rules → 01-functional → 06-data-model
        → 08-concurrency-model → 09-crash-recovery-model → 05-exception-matrix
        → 07-configuration-model → 10-security-model → 11-observability-model
        → 02-nfr → 12-acceptance-criteria → 13-open-questions-risks
```

理由：先建立**状态机与业务规则**这两个「不变量骨架」，再看功能域与数据模型，最后看横切关注点（配置/安全/可观测）与验收。

### 2.2 按角色

| 角色 | 推荐路径 | 关注点 |
|---|---|---|
| 业务/需求方 | `03` → `04`（§6 核心问题）→ `12` → `13` | 业务规则、旧版本不阻塞新版本的承诺、验收口径、待确认问题 |
| 架构师 | `04` → `06` → `08` → `09` → `13`（§5 架构问题） | 状态机、数据模型约束、并发与恢复、架构缺口 |
| 开发（Scanner） | `01`（FR-01/02/03）→ `07` → `06` → `05` | 扫描/稳定/版本、目录配置、唯一约束、异常处理 |
| 开发（Worker/S3/Gateway） | `01`（FR-04–FR-06）→ `04` → `08` → `05` → `09` | 任务创建、迁移矩阵、claim/lease、异常、崩溃恢复 |
| 运维/SRE | `11` → `07` → `10` → `13`（§4 风险） | 指标/日志/告警/查询配方、配置、安全、风险 |
| 测试 | `12` → `04` → `05` → `03` | 可执行验收标准、迁移用例、异常场景、规则映射 |

## 3. Phase 0 决策 → 整体设计文档章节映射表

| Phase 0 决策 / 内容 | 所在文档 | 整体设计文档章节 |
|---|---|---|
| 9 个状态集合（禁止新增） | `04` §1 | §13 状态机、§69 关键不变量 |
| 迁移矩阵 T1–T23 | `04` §2 | §13 状态机、§14 为什么不把 worker claim 做成状态 |
| 非法迁移清单 | `04` §3 | §53 状态迁移必须集中管理 |
| claim 不是状态 | `04` §4 | §14 为什么不把 worker claim 做成状态、§15 Worker Claim |
| supersede / latest wins | `04` §6、`13` §3 | §19 同一路径版本串行、§20 同一路径串行的数据库约束、§21 版本最终一致性、§77 S3 覆盖与同文件串行 |
| S3 覆盖竞态与宽限期 | `04` §6.6、`13` §5（A1） | §40 S3 Object Key、§41 S3 上传、§77 S3 覆盖与同文件串行 |
| 状态机不变量 S1–S9 | `04` §7 | §69 关键不变量 |
| 业务规则 BR-01–BR-65 | `03` | §2 已确认的业务约束、§25–§26 Retry、§50–§54 保留/审计 |
| 稳定 = 连续两次扫描一致 | `01` FR-02、`03`、`13` Q1 | §9 文件稳定性检测、§11 扫描过程 |
| fingerprint ≠ 内容 hash | `03`、`06`、`13` Q2 | §8 为什么不计算文件 SHA-256、§32 file、§33 file_version |
| S3 Object Key = `<customer-space>/<relative-path>` | `03`、`06`、`07` | §40 S3 Object Key |
| 一 directory 一 customer space | `03`、`06`、`07` | §2.2 目录与客户、§31 推荐 PostgreSQL 表（directory） |
| 同 path 版本默认串行 | `04`、`08` | §19、§20、§38 同一路径串行的实现 |
| 失败任务永不删除 / 无限重试 | `03`、`13` §4（R-01） | §49 Gateway 失败、§50 不删除失败任务、§86 故障场景矩阵 |
| max_retry_count 是长期重试阈值 | `03` BR-44、`07`、`08` | §25 Retry、§26 Exponential Backoff |
| 数据保留 1 年（仅清终态） | `06` §6、`13` §5（A7） | §54 数据保留 |
| 密钥不入 PostgreSQL | `03`、`07`、`10` | §56 敏感信息、§55 不建议 PostgreSQL 保存应用日志 |
| 7 必需表 + `transfer_event` | `06` | §31 推荐 PostgreSQL 表、§32–§35、§30 数据库核心关系 |
| 唯一约束作为重复最终防线 | `06` §3 | §20、§37 为什么数据库约束非常重要 |
| 索引策略 | `06` §5 | §82 推荐索引、§83 建议不要过度索引 |
| 事务边界 | `06` §7 | §36 任务创建的事务边界、§66 数据库事务原则 |
| 全局 / 目录级并发上限 | `08` | §42 一个文件默认一个 Worker、§43 并发控制、§44 为什么要限制并发 |
| DB worker claim（`FOR UPDATE SKIP LOCKED`） | `08` | §15 Worker Claim、§38 同一路径串行的实现 |
| lease 独立于业务状态 | `08`、`09` | §16 Lease、§17 Crash Recovery |
| 有界队列与背压 | `08` | §45 Scheduler 与 Worker 解耦、§46 Dispatcher、§47 背压 |
| Virtual Thread 也要限流 | `08` | §70 Java 21 技术建议、§71 Virtual Threads 是否使用、§72 推荐的并发结构 |
| 4 个崩溃点与恢复动作 | `09` | §17 Crash Recovery、§64 JVM Crash 流程、§65 Scanner Crash |
| 启动恢复顺序 | `09` | §84 系统启动恢复 |
| 优雅关闭（SIGTERM） | `09` | §85 Graceful Shutdown |
| 9 类异常 / 60 场景 | `05` | §86 故障场景矩阵、§12 文件不存在处理、§49 Gateway 失败、§75 S3 上传失败时 |
| 结果未知（UNKNOWN）统一处理 | `05`、`13` §5（A4） | §24 Gateway 超时、§68 Exactly-once 的处理 |
| application.yml 配置树 | `07` §2 | §5 配置设计 |
| 配置快照 `config_version` | `07` §5 | §6 配置快照、§78 配置修改 |
| 环境变量 / Secret 注入 | `07` §4、`10` | §56 敏感信息 |
| 指标清单（scan/discovered/stable/backlog/...） | `11` §指标 | §58 Metrics |
| 结构化日志必需字段 | `11` §日志 | §57 日志设计 |
| 告警清单 | `11` §告警 | §59 最重要的告警 |
| 运维查询配方（「为什么还没到客户？」） | `11` §配方 | §60 运维查询模型 |
| 验收标准（11 模块 + 5 维度） | `12` | §92 V1 验收标准 |
| 8 条【架构问题】A1–A8 | `13` §5 | §33/§34/§35/§38/§39/§50/§54/§80 |
| 开放问题 Q1–Q6 | `13` §2 | §9、§22–§24、§51、§68 |

## 4. 权威事实源与一致性规则

### 4.1 事实源优先级

```text
1) 用户已确认的 Goal 契约与《整体技术设计.md》（已确认架构，不得擅自修改）
2) 本目录 04-state-machine.md（状态集合 / 迁移矩阵 / supersede 规则的权威版本）
3) 本目录 03-business-rules.md（业务规则权威版本）
4) 本目录 06-data-model.md（数据模型约束权威版本）
5) 其他 01/02/05/07–12（各自领域的展开）
6) 13-open-questions-risks.md（未决项与冲突登记，仅登记不决策）
```

### 4.2 一致性规则

1. **状态名必须逐字一致**：`DISCOVERED`、`STABILITY_CHECK`、`READY`、`UPLOADING`、`S3_UPLOADED`、`GATEWAY_DELIVERING`、`WAITING_RETRY`、`DELIVERED`、`CANCELLED`。任何文档不得引入 `FAILED` / `CLAIMED` / `PROCESSING` / `SUPERSEDED` 作为业务状态。
2. **阈值单一来源**：`worker.upload.maxConcurrency=8`、`worker.gateway.maxConcurrency=8`、`lease.duration=5m`、retry `initialDelay=30s / multiplier=2 / maxDelay=1h / jitter=20%`、`supersede.afterFailedAttempts=5`、`supersede.afterWaiting=30m`、`supersede.gracePeriod=15m`。若某文档与之不一致，以 `04` / `07` 为准并修正该文档。
3. **冲突只标注不改架构**：任何与已确认架构的冲突，一律写 `【架构问题】`（问题 / 风险 / 建议方案 / 对现有设计的影响），登记在 `13`，**不自行修改设计**。
4. **密钥零出现**：全部占位符。

## 5. 状态集合与关键阈值速查

### 5.1 9 个状态

| 状态 | 语义 | 终态 |
|---|---|---|
| `DISCOVERED` | 已发现，待稳定检测 | 否 |
| `STABILITY_CHECK` | 稳定检测中 | 否 |
| `READY` | 待执行（可被 claim） | 否 |
| `UPLOADING` | 正在读 NAS 并上传 S3 | 否 |
| `S3_UPLOADED` | S3 上传成功，待 Gateway 投递 | 否 |
| `GATEWAY_DELIVERING` | Gateway 投递中 | 否 |
| `WAITING_RETRY` | 等待下次重试（含长期重试） | 否 |
| `DELIVERED` | 已成功投递客户 | **是** |
| `CANCELLED` | 已取消（含被 supersede） | **是** |

### 5.2 关键阈值

| 配置 | 默认值 | 说明 |
|---|---|---|
| `worker.upload.maxConcurrency` | `8` | S3 上传全局并发 |
| `worker.gateway.maxConcurrency` | `8` | Gateway 投递全局并发 |
| `directory.max-concurrency` | `2~4` | 目录级并发上限 |
| `lease.duration` | `5m` | lease 时长 |
| `retry.initialDelay` | `30s` | 退避初值 |
| `retry.multiplier` | `2` | 退避倍数 |
| `retry.maxDelay` | `1h` | 退避上界 |
| `retry.jitter` | `20%` | 抖动 |
| `supersede.afterFailedAttempts` | `5` | 进入 supersede 的失败次数阈值 |
| `supersede.afterWaiting` | `30m` | 进入 supersede 的等待时长阈值 |
| `supersede.gracePeriod` | `15m`（≥ `gateway.readTimeout + lease.duration + 5m`） | supersede 宽限期 |
| 数据保留 | `1 年` | 仅清理终态 |

### 5.3 核心承诺

> **同一 NAS path 的旧版本永久 Gateway 失败时，最新版本最坏在约 5~6 小时内获得执行权**（supersede），旧版本置 `CANCELLED`（`cancel_reason=SUPERSEDED_BY_NEWER_VERSION`）。详见 [`04-state-machine.md`](04-state-machine.md) §6 与 [`13-open-questions-risks.md`](13-open-questions-risks.md) §3。

## 6. 术语表

| 术语 | 定义 |
|---|---|
| **directory** | NAS 上的一个被监控目录，配置的最小业务单元，唯一对应一个 customer space |
| **file** | 同一 `(nas_id, directory_id, relative_path)` 的逻辑文件 |
| **file_version** | file 的一个内容版本，由 `fingerprint`（path+size+mtime）区分 |
| **transfer_task** | 一个 file_version 的投递任务，1:1 |
| **transfer_attempt** | 一次执行尝试（S3 上传或 Gateway 投递） |
| **transfer_event** | 状态迁移/人工操作的审计事件（可选表） |
| **claim** | worker 从 DB 抢占任务的动作，**不是状态** |
| **lease** | worker 对任务的租约（`worker_id` / `claimed_at` / `lease_until`），独立于业务状态 |
| **supersede** | 新稳定版本取消旧版本的执行权（旧版本 → `CANCELLED`） |
| **backlog** | DB 中等待执行的 `READY` / `WAITING_RETRY` 任务 |
| **stable** | 连续两次扫描 `path + size + mtime` 完全一致 |
| **fingerprint** | `path + size + mtime` 的摘要，用于版本判定，**不是内容 hash** |
| **At-least-once** | 至少一次投递语义，允许重复投递 |
| **outcome** | attempt 结果：`SUCCESS` / `FAILURE` / `UNKNOWN`（UNKNOWN 表示结果未知） |

## 7. 边界声明与 Phase 0 完成定义

### 7.1 本阶段交付边界

- ✅ 仅新增 `docs/phase-0/` 下的 14 个中文 Markdown 文件。
- ❌ 不新增 `*.java`、`*.sql`、`pom.xml`、`mvnw`；不创建 Maven 工程。
- ❌ 不修改、不重命名、不移动两份既有设计文档（哈希前后一致）。
- ❌ 不执行 `git commit` / `git push`；不连接 PostgreSQL。
- ❌ 不进入 Phase 1。

### 7.2 完成定义

Phase 0 完成 = 14 个文件齐备 + 13 部分内容覆盖 + 边界未被跨越 + 两份既有文档哈希未变 + 待确认问题与架构问题已登记。

### 7.3 下一步

```text
Phase 0（本目录） → 等待用户确认 → Phase 1 工程骨架（Maven/Spring Boot 骨架，无业务逻辑）
```

进入 Phase 1 前建议先裁决 [`13-open-questions-risks.md`](13-open-questions-risks.md) 中 Q2（fingerprint 口径）与 Q3（Gateway 契约），因其影响数据模型与 attempt 语义。
