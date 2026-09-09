# 03 Business Rules（业务规则）

## 本部分范围

把提示词与整体设计中**隐含的、口头的、散落各处的**业务规则，全部显式化、编号化，并给出每条规则的**依据、违反后果、强制机制**。

- 规则编号 `BR-xx`，本部分共 **65 条**（满足"≥25 条"要求）。
- 每条规则都是可判定的（能写出正/反测试用例）。
- 规则之间冲突时按第 4 节的优先级裁决。

## 本部分不做什么

- 不定义状态迁移（见 `04-state-machine.md`）。
- 不定义表结构与字段（见 `06-data-model.md`）。
- 不定义配置项默认值（见 `07-configuration-model.md`）。
- 不写代码、不写 DDL。

## 对应整体设计文档章节

§2（已确认业务约束）、§6（配置快照）、§7–§9（文件模型/稳定性）、§12（文件不存在）、§19–§21（同路径串行/版本一致性）、§25–§26（Retry）、§40–§41（S3 Object Key/上传）、§43–§44（并发）、§50–§51（不删除/人工重试）、§54（数据保留）、§56（敏感信息）、§69（不变量）、§79（不引入的东西）、§92（验收标准）。

---

## 1. 业务规则清单

### A. 目录与客户空间

| # | 规则 | 依据 | 违反后果 | 强制机制 |
|---|---|---|---|---|
| BR-01 | **一个 directory 只对应一个 customer space**；不允许一个目录服务多个客户 | §2.2 | 文件投递到错误客户（严重数据事故） | `directory.customer_space_id` 非空 FK；配置校验拒绝多客户目录 |
| BR-02 | 一个 customer space 可对应多个 directory | §2.2 | 无（正常业务） | `customer_space 1:N directory` |
| BR-03 | 只有 `enabled = true` 的 directory 才参与扫描 | §5 | 停用目录仍被扫描 | Scanner 只加载 enabled 目录；停用时在途任务按 BR-37 处理 |
| BR-04 | directory 的业务配置（挂载路径、相对路径、扫描周期、后缀、客户空间、并发限制）**独立配置**，互不影响 | 提示词 §六 | 一个目录的变更影响其他目录 | 配置结构为 `nas.directories[]`，每项独立 |
| BR-05 | `(nas_id, path)` 在 directory 中唯一 | §31 | 同一目录被扫描两次，重复任务 | `UNIQUE(nas_id, path)` |

### B. 文件发现

| # | 规则 | 依据 | 违反后果 | 强制机制 |
|---|---|---|---|---|
| BR-06 | Scanner **只负责发现事实**，不执行耗时传输（不做 S3 PUT / Gateway 调用） | §3、§45 | Scanner 被外部系统拖死，扫描停摆 | 模块边界：scanner 不依赖 storage/gateway；代码评审 |
| BR-07 | 只有后缀命中 `directory.extensions` 的文件才被纳入 | §5、§11 | 无关文件被投递到客户 | Scanner 后缀过滤（大小写策略需统一，见 `13-` 开放问题） |
| BR-08 | 文件逻辑身份 = `(nas_id, directory_id, relative_path)` | §2.4、§7 | 同路径被当作两个文件，破坏串行 | `UNIQUE(nas_id, directory_id, relative_path)` |
| BR-09 | 扫描采用"全目录扫描 + DB 增量判断"；**不得** `Files.readAllBytes()`，必须流式 | §11、§73 | OOM、NAS IO 爆炸 | 代码规范 + 静态检查；上传路径用 `InputStream` |
| BR-10 | Scanner 重复扫描**不得**产生重复 task | §36–§37、§65 | 同一文件被投递多次（虽允许但无谓） | `UNIQUE(file_version_id)` + `INSERT ... ON CONFLICT DO NOTHING` |
| BR-11 | Scanner 中途崩溃不需要 checkpoint；重启后重扫，靠唯一约束保证正确 | §65 | 无（设计允许） | 幂等落库 |
| BR-12 | 单个目录扫描失败**不得**导致该目录调度永久停止 | 提示词 §Phase5 | 目录静默失联，文件丢失 | 调度器捕获异常 + 记录 + 下周期继续 + 连续失败告警 |
| BR-13 | 扫描周期由 directory 配置决定，范围从几分钟到一天 | §2.3 | 及时性不达标 | `directory.scan_interval_sec` |

### C. 文件稳定性

| # | 规则 | 依据 | 违反后果 | 强制机制 |
|---|---|---|---|---|
| BR-14 | **稳定 = 连续两次扫描观察到 `path`、`size`、`mtime` 完全一致** | §2.3、§9 | 上传半成品文件，客户收到残缺数据 | `FileStabilityChecker` + `stable_at` |
| BR-15 | `fingerprint = SHA-256(relative_path + size + mtime)`，**不是文件内容 hash**；不得为发现变化而读取文件内容 | §7、§8 | 无谓的 NAS IO 与网络流量 | `fingerprint.strategy = SIZE_MTIME`（V1 唯一策略） |
| BR-16 | 若两次观察间 `fingerprint` 变化，稳定性判定**重新开始**（不是"累计观察次数"） | §9 | 用两次不一致的观察误判稳定 | T3 重置稳定基线 |
| BR-17 | 业务前提：**文件一旦进入稳定状态，上游不得继续修改** | §2.3 | 上传内容与 mtime 声明不符 | 业务约定 + 投递后由上游负责；系统不额外校验内容 hash |
| BR-18 | 未稳定的文件**绝不**进入上传阶段 | §9、§13 | 半成品投递 | T4 守卫；`STABILITY_CHECK → UPLOADING` 属非法迁移 |

### D. 文件版本

| # | 规则 | 依据 | 违反后果 | 强制机制 |
|---|---|---|---|---|
| BR-19 | 文件版本通过 `size + mtime` 识别；同 `file_id` 下 `fingerprint` 唯一 | §2.4、§33 | 重复版本记录 | `UNIQUE(file_id, fingerprint)` |
| BR-20 | 同一 `file_id` 的版本必须有序（`version_no` 单调递增） | §39 | 串行与 supersede 判定不确定 | `UNIQUE(file_id, version_no)`（见【架构问题】A2） |
| BR-21 | 版本历史在 PostgreSQL 保留 1 年 | §2.4、§54 | 无法追溯 | 保留策略 BR-40 |
| BR-22 | 客户最终只要求**当前版本**可靠投递；不要求每个历史版本都投递 | §21 | 若强求历史版本，v1 永久阻塞 v2 | latest wins（BR-28–BR-32） |

### E. 任务创建与唯一性

| # | 规则 | 依据 | 违反后果 | 强制机制 |
|---|---|---|---|---|
| BR-23 | **一个 file_version 最多一个 transfer_task** | §69 不变量 1 | 同版本被并行上传/投递 | `UNIQUE(file_version_id)` |
| BR-24 | 任务创建必须由数据库唯一约束作为**最终防线**，不能只依赖 Java 层判断 | 提示词 §五、§37 | 并发/重复扫描下产生重复任务 | `ON CONFLICT DO NOTHING` |
| BR-25 | `file` + `file_version` + `transfer_task` 的创建必须在**同一事务**内 | §36、§66 | 出现孤儿版本或孤儿任务 | 单事务写入 |
| BR-26 | 任务创建时必须固化配置快照（`s3_bucket`、`s3_object_key`、`gateway_route`、`config_version` 等） | §6 | 配置变更后旧任务投递到错误目标 | 快照列不可变 |
| BR-27 | 配置修改只影响**新任务**；已有任务继续使用创建时的快照 | §78 | 在途任务目标漂移 | 快照 + 重启生效语义 |

### F. supersede / latest wins

| # | 规则 | 依据 | 违反后果 | 强制机制 |
|---|---|---|---|---|
| BR-28 | 同一 `file_id` 的不同版本**默认串行**：更早版本处于非终态时，更晚版本不得进入执行阶段 | §19、§20 | v1 Gateway 读到被 v2 覆盖的 S3 对象 | 串行守卫（`NOT EXISTS` 子查询） |
| BR-29 | 不同 `file_id` 的文件**必须可并行**（受全局/目录并发限制） | §19、§43 | 吞吐塌陷 | 并发模型（`08-concurrency-model.md`） |
| BR-30 | **旧版本长期失败时，必须被更新稳定版本 supersede**（`CANCELLED`），不得永久阻塞新版本 | 提示词 §十 | 最新版本永远投递不出去 | `SupersedeService`（`04-state-machine.md` 6.3） |
| BR-31 | supersede 仅允许在旧版本 **lease 不活跃** 且越过"失败次数或等待时间"阈值 且 **宽限期已过** 时发生 | `04-state-machine.md` 6.3 | 打断在途 Gateway 调用，产生覆盖竞态 | 三条守卫同时校验 |
| BR-32 | `DELIVERED` 版本**永不**被 supersede、**永不**自动重投；`CANCELLED` 为终态不可复活 | §69、`04-state-machine.md` 6.7 | 历史事实被篡改 | 状态机拒绝非法迁移 |
| BR-33 | supersede 只改状态，**不删除**任何任务、attempt、event 记录 | 提示词 §九 | 丢失审计 | 只做 UPDATE |
| BR-34 | 被 supersede 的旧版本已上传的 S3 对象**无需清理**：新版本覆盖同一 Key 即可 | §21、§40 | 无（设计允许） | S3 Key 稳定命名（BR-35） |

### G. S3

| # | 规则 | 依据 | 违反后果 | 强制机制 |
|---|---|---|---|---|
| BR-35 | S3 Object Key **稳定**，形如 `<customer-space>/<relative-path>`；**禁止**包含 UUID / timestamp / taskId / version_no / fingerprint / 随机后缀 | 提示词 §十一、§40 | 新版本无法覆盖旧版本，S3 无限膨胀，Gateway 读错版本 | Key 生成函数唯一实现 + 单元测试断言 |
| BR-36 | S3 **不启用版本控制**；新版本直接覆盖 | §2.4、§79 | 若启用版本控制，客户可能读到旧版本 | 桶配置 + 部署检查 |
| BR-37 | 上传成功（S3 PUT 200）**不等于**任务完成；只有 Gateway 明确成功才是 `DELIVERED` | §76 | 客户未收到文件却标记完成（严重） | 状态机 T13 唯一产生 `DELIVERED` |
| BR-38 | 必须流式上传（`InputStream` → S3 request body），不得整文件读入内存 | §73、§74 | OOM | 接口 `ObjectStorageUploader` + 代码评审 |
| BR-39 | 每次上传 attempt 独立打开 NAS 文件；失败后**重新打开**，不得依赖 InputStream rewind | §74 | 重试读到已失效的流 | attempt 生命周期内 open/close |
| BR-40 | S3 上传接口必须抽象，为未来 Multipart 留扩展点，但 V1 只实现普通 PutObject | §41、§79 | 过度设计 | 接口 `ObjectStorageUploader` |

### H. Gateway

| # | 规则 | 依据 | 违反后果 | 强制机制 |
|---|---|---|---|---|
| BR-41 | Gateway 调用的**成功定义**：Gateway 明确返回成功；其他一切情况（含超时、响应丢失）按失败处理 | §22、§24、§68 | 把未知结果当成功，文件丢失 | T15 规则 |
| BR-42 | 允许重复投递（At-least-once）；客户可能收到重复文件，业务已接受 | §24、§68 | 无（业务接受） | 设计选择 |

### I. 重试与不放弃

| # | 规则 | 依据 | 违反后果 | 强制机制 |
|---|---|---|---|---|
| BR-43 | 重试采用指数退避：`initialDelay` 30s、`multiplier` 2、`maxDelay` 1h、`jitter` 20% | §26 | 重试风暴或恢复过慢 | `RetryScheduler` 计算 `next_retry_at` |
| BR-44 | **`max_retry_count` 只是"进入长期重试"的阈值，不是永久停止条件**；超过后仍为 `WAITING_RETRY` 并继续重试直到成功 | 提示词 §九、§26 | 文件永久丢失（最严重） | 状态机无 `FAILED`；迁移矩阵 T14/T15/T17 |
| BR-45 | 失败任务**永不**自动删除（任务、S3 对象、历史记录都不删） | §50 | 无法恢复、无法追责 | 无删除路径；保留策略只清终态 |
| BR-46 | 重试不占用 worker：失败即释放 lease，回到 `WAITING_RETRY` 等待 `next_retry_at` | §25 | worker 被长退避占死 | T14/T15 释放 lease |

### J. 并发与资源保护

| # | 规则 | 依据 | 违反后果 | 强制机制 |
|---|---|---|---|---|
| BR-47 | **禁止无限制并发**：全局上传并发、全局 Gateway 并发、目录级并发三层限制，实际并发 = `min(阶段全局, 目录)` | §43、§44、§72 | NAS IO / S3 连接 / Gateway 请求 / JVM 资源被打爆 | Semaphore + 有界队列 |
| BR-48 | 同一任务同一时刻**只能被一个 worker** 执行 | §15、§69 不变量 2 | 重复上传、状态错乱 | `FOR UPDATE SKIP LOCKED` + lease |
| BR-49 | 任务 claim 不得写成 `SELECT ... WHERE status='READY'` 后直接执行 | 提示词 §八 | 两个 worker 抢同一任务 | 强制走 `TaskClaimService` |
| BR-50 | 队列必须**有界**；队列满时停止 claim，DB 承担 backlog | §46、§47 | JVM OOM、DB 被拖垮 | 有界 executor + 背压 |

### K. 文件消失与删除

| # | 规则 | 依据 | 违反后果 | 强制机制 |
|---|---|---|---|---|
| BR-51 | 文件在**开始读取 NAS 前**消失 → 任务可 `CANCELLED` | §12 | 无（设计允许） | T5 / T7 |
| BR-52 | 文件在 `UPLOADING` 期间消失 → **不立即取消**，让当前上传尽量完成 | §12、提示词 §十三 | 浪费一次传输（可接受） | T8/T9 不检查文件存在性 |
| BR-53 | S3 上传成功后，后续阶段（`S3_UPLOADED` / `GATEWAY_DELIVERING` / `WAITING_RETRY`）**不依赖 NAS 文件**；NAS 文件消失不影响这些阶段 | 提示词 §十三 | 已成功上传的任务被误取消 | 重投只读 S3，不读 NAS |

### L. 数据保留

| # | 规则 | 依据 | 违反后果 | 强制机制 |
|---|---|---|---|---|
| BR-54 | 业务数据（file / file_version / transfer_task / transfer_attempt / transfer_event）保留 **1 年** | §54 | 无限增长 | 定时清理任务 |
| BR-55 | 清理**只针对终态**（`DELIVERED` / `CANCELLED`）且超 1 年的记录；**非终态任务永不清理** | §50 与 §54 的冲突裁决 | 删除仍在重试的任务 = 丢文件 | 清理 SQL 强制 `status IN (...)` 且 `only-terminal-states = true` |
| BR-56 | 清理按 FK 依赖顺序：`transfer_event` → `transfer_attempt` → `transfer_task` → `file_version` → `file` | §54 | FK 冲突导致清理失败 | 顺序化删除 |
| BR-57 | 应用日志**不进** PostgreSQL | §55 | DB 被日志淹没 | 日志输出到 stdout，由外部采集 |

### M. 安全

| # | 规则 | 依据 | 违反后果 | 强制机制 |
|---|---|---|---|---|
| BR-58 | S3 AccessKey/SecretKey、Gateway AppId/AppKey **不得明文存 PostgreSQL**，**不得进 Git**，**不得进日志** | §56、提示词 §六 | 凭据泄露 | 环境变量/Secret 注入 + 日志脱敏 |
| BR-59 | 敏感配置只通过环境变量 / Secret 外部注入 | §56 | 同上 | `07-`/`10-` 文档；配置校验 |

### N. 人工操作

| # | 规则 | 依据 | 违反后果 | 强制机制 |
|---|---|---|---|---|
| BR-60 | 人工重试只能作用于合法任务，且不得破坏状态机 | 提示词 §Phase14 | 状态错乱 | 走 `TaskStateMachine`（T19） |
| BR-61 | 人工重试老版本必须遵守 latest wins：非最新稳定版本一律拒绝 | `04-state-machine.md` 6.7 | 复活老版本，阻塞新版本 | T19 守卫校验 `latest_stable` |
| BR-62 | 人工操作必须留痕（`transfer_event`，含 operator 与 reason） | 提示词 §Phase14、§52 | 无法追责 | 同事务写 event |

### O. 可观测与可追踪

| # | 规则 | 依据 | 违反后果 | 强制机制 |
|---|---|---|---|---|
| BR-63 | 任何文件都必须可沿 `filePath → file → file_version → task → attempt → gateway` 追踪 | 提示词 §十八、§60 | 无法回答"文件到底送到没有" | 全链路外键 + 结构化日志字段 |
| BR-64 | 所有日志必须带 `taskId`、`fileVersionId`、`fileId`、`directoryId`、`customerSpace`、`filePath` | §57 | 无法串联排查 | 日志规范（`11-` 文档） |
| BR-65 | 所有状态迁移必须可追踪（`transfer_event`）；所有执行尝试必须可追踪（`transfer_attempt`） | §52 | 运维盲区 | 同事务写入 |

---

## 2. 规则与状态机的对应关系

| 规则组 | 对应状态迁移 |
|---|---|
| BR-14–BR-18 稳定性 | T1–T5 |
| BR-23–BR-27 任务创建 | T1、T6 |
| BR-28–BR-34 串行/supersede | T6、T20、T21 |
| BR-35–BR-40 S3 | T8、T9、T10 |
| BR-41–BR-42 Gateway | T11–T16 |
| BR-43–BR-46 重试 | T9、T14、T15、T17、T18 |
| BR-51–BR-53 文件消失 | T5、T7 |
| BR-60–BR-62 人工操作 | T19 |

---

## 3. 规则的强制机制分层

| 层 | 机制 | 覆盖规则 |
|---|---|---|
| L1 数据库约束 | UNIQUE / FK / CHECK / NOT NULL | BR-05、BR-08、BR-19、BR-23、BR-24 |
| L2 状态机 | `TaskStateMachine` 校验 + 条件更新 | BR-18、BR-28、BR-32、BR-37、BR-44、BR-60、BR-61 |
| L3 并发原语 | `FOR UPDATE SKIP LOCKED` + lease + Semaphore + 有界队列 | BR-47–BR-50 |
| L4 配置校验 | 启动期 fail-fast | BR-03、BR-04、BR-05、BR-58、BR-59 |
| L5 代码规范与测试 | 代码评审、单元/集成测试、静态检查 | BR-06、BR-09、BR-35、BR-38、BR-39、BR-63–BR-65 |
| L6 运维与告警 | 指标、告警、Runbook | BR-12、BR-46、BR-63 |

> **原则**：越是"丢文件"后果的规则，越要落在 L1/L2（数据库与状态机），不能只靠 L5（代码自觉）。

---

## 4. 规则冲突与优先级裁决

| 冲突 | 裁决 | 理由 |
|---|---|---|
| BR-45「失败任务永不删除」 vs BR-54「数据保留 1 年」 | **非终态永不清理**；只清终态且超 1 年（BR-55） | 不丢文件优先级最高 |
| BR-28「同路径串行」 vs BR-30「旧版本不得阻塞新版本」 | 串行为默认；越过阈值后 supersede 解除串行（BR-30、BR-31） | 既要版本语义正确，又要最新版本最终投递 |
| BR-42「允许重复投递」 vs "不要重复打扰客户" | 允许重复（At-least-once 优先） | 业务明确接受重复、不接受丢失 |
| BR-22「只保证当前版本」 vs "每个版本都要投递" | 只保证当前版本 | 业务明确取舍 |
| BR-52「上传中不取消」 vs "NAS 文件消失应尽快释放资源" | 让当前上传完成 | 已打开句柄的读取与目录删除是两件事 |
| BR-47「限制并发」 vs "尽快处理该目录所有文件" | 用"足够高但有上限"的并发实现 | 保护 NAS/S3/Gateway |

**总原则（唯一最高优先级）**：

> **文件可以重复，但不能因为中转应用的故障而永久丢失。**

---

## 5. 业务规则验收映射

| 规则 | 验收方式（详见 `12-acceptance-criteria.md`） |
|---|---|
| BR-01、BR-05 | 配置校验用例：多客户目录被拒绝、重复 path 被拒绝 |
| BR-14–BR-18 | 稳定性集成测试：首次/第二次/变化/消失/重现 |
| BR-23–BR-25 | 并发扫描测试：同一文件只产生一个 task |
| BR-28–BR-34 | supersede 测试：READY / WAITING_RETRY / GATEWAY_DELIVERING 三种情形 |
| BR-35 | Key 生成单元测试：不含 UUID/timestamp/version |
| BR-37 | 端到端测试：S3 成功但 Gateway 失败 → 不得为 `DELIVERED` |
| BR-43–BR-46 | 重试测试：退避曲线、超过阈值仍继续 |
| BR-47–BR-50 | 并发测试：多 worker、lease 过期、背压 |
| BR-51–BR-53 | 文件消失测试：三个阶段各一个用例 |
| BR-54–BR-57 | 保留测试：终态被清、非终态不被清 |
| BR-58、BR-59 | 安全审计：DB 与日志中不含凭据 |
| BR-60–BR-62 | manual retry 测试：4 种拒绝情形 + 留痕 |
| BR-63–BR-65 | 可追踪测试：从 filePath 查到 attempt 与 gateway 结果 |
