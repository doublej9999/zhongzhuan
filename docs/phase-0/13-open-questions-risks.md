# 13 Open Questions / Risks（开放问题与风险）

## 本部分范围

本部分是 Phase 0 的**收敛口**，负责把前面 12 个部分中所有「尚未定论」「需要业务或对方系统确认」「与已确认架构存在张力」的内容集中登记，并给出：

1. **待确认问题清单**（Q1–Q6 及衍生运维问题）：问题描述、为什么必须确认、当前默认假设、影响范围、阻塞阶段。
2. **核心问题的显式回答**：同一 NAS path 的旧版本永久 Gateway 失败时，如何保证最新版本不被阻塞（判定规则、阈值、迁移路径、对 S3 覆盖语义的影响）。
3. **风险登记表**：风险描述、类别、概率、影响、等级、缓解措施、残余风险。
4. **全部 `【架构问题】`（A1–A8）**：每条含 **问题 / 风险 / 建议方案 / 对现有设计的影响**，且**保持原设计不变**，仅登记、不擅自修改。

本部分的每一条都必须在进入 Phase 1（工程骨架）之前给出「已确认」或「接受默认值」的结论，否则相关实现会返工。

## 本部分不做什么

- **不修改已确认架构**：状态集合仍为 9 个，不新增 `FAILED` / `CLAIMED` / `PROCESSING` / `SUPERSEDED`；不引入 MQ / Redis / Kubernetes / 分布式事务 / 分布式锁 / S3 对象版本控制 / 内容 hash / Multipart 全套 / Admin 前端。
- **不做决策**：本部分只登记问题、给出候选方案与建议，最终裁决权在用户；未经确认不得按「建议方案」直接改设计。
- **不写代码、不写 DDL、不写配置文件**：所有 SQL / yml 片段均为语义示意，不是交付物。
- **不重复展开**：状态迁移矩阵见 `04-state-machine.md`，业务规则见 `03-business-rules.md`，异常场景见 `05-exception-matrix.md`，此处只做引用与汇总。
- **不写真实密钥**：涉及凭据一律占位符（如 `${S3_SECRET_KEY}`）。

## 对应整体设计文档章节

| 本部分内容 | 整体设计文档章节 |
|---|---|
| 待确认问题（扫描周期/稳定复检） | §9 文件稳定性检测、§10 Scanner 设计、§11 扫描过程 |
| 待确认问题（mtime 精度与 fingerprint） | §7 文件模型、§9 文件稳定性检测、§32 file、§33 file_version |
| 待确认问题（Gateway 契约/幂等键） | §22 Gateway API、§23 Gateway 调用时序、§24 Gateway 超时、§68 Exactly-once |
| 待确认问题（manual retry 触发 S3 重传） | §51 人工强制重试、§76 S3 成功后不要立即认为任务完成 |
| 待确认问题（客户 SFTP 目录/文件名） | §22 Gateway API、§62 文件修改流程 |
| 待确认问题（上传后校验） | §41 S3 上传、§75 S3 上传失败时 |
| 核心问题（旧版本不阻塞新版本） | §19 同一路径版本串行、§20 同一路径串行的数据库约束、§21 版本最终一致性、§77 S3 覆盖与同文件串行 |
| 风险登记表 | §86 故障场景矩阵、§69 关键不变量、§59 最重要的告警、§54 数据保留、§56 敏感信息 |
| 【架构问题】A1 | §19、§20、§21、§40、§77 |
| 【架构问题】A2 | §33 file_version、§39 推荐增加 version_no |
| 【架构问题】A3 | §34 transfer_task、§38 同一路径串行的实现 |
| 【架构问题】A4 | §29 transfer_attempt、§35 transfer_attempt、§24 Gateway 超时 |
| 【架构问题】A5 | §33 file_version、§34 transfer_task、§13 状态机 |
| 【架构问题】A6 | §5 配置设计、§31 推荐 PostgreSQL 表（directory）、§40 S3 Object Key |
| 【架构问题】A7 | §50 不删除失败任务、§54 数据保留 |
| 【架构问题】A8 | §10 Scanner 设计、§45 Scheduler 与 Worker 解耦、§80 未来演进路线（V5 水平扩展） |

---

## 1. 使用说明与状态定义

### 1.1 条目状态

| 状态 | 含义 | 处置 |
|---|---|---|
| **OPEN** | 尚未确认，按「当前默认假设」推进 | 不阻塞 Phase 0 交付；阻塞其标注的阶段 |
| **ACCEPTED** | 用户已确认按默认假设执行 | 记入对应文档，作为事实源 |
| **CHANGED** | 用户决定修改已确认架构 | 必须同步更新 `03`–`12` 相关章节与基线 |
| **DEFERRED** | 明确推迟到 V2+ | 只在 `07`/`08` 留扩展点，不实现 |

### 1.2 收敛原则

1. **能靠默认值先走通的，先走通**：Phase 0 不因开放问题停摆；但每条开放问题必须写明「默认假设」，使 Phase 1+ 可编译、可测试。
2. **影响数据模型或状态机的问题优先确认**：Q2（fingerprint 取值）影响 `UNIQUE(file_id, fingerprint)`；Q3（Gateway 契约）影响 `transfer_attempt.outcome`。
3. **影响业务承诺的问题由用户裁决**：Q1、Q4、Q5、Q6。
4. **【架构问题】不自行修复**：A1–A8 只登记，待用户确认后在 Phase 2 的 DDL / Phase 3 的实现中统一落地。

---

## 2. 待确认问题清单（Q1–Q6）

### 2.1 总表

| 编号 | 问题 | 为什么必须确认 | 当前默认假设 | 影响范围 | 阻塞阶段 | 状态 |
|---|---|---|---|---|---|---|
| **Q1** | 扫描周期为 24h 的目录，「稳定」是否需要等一整个周期？是否引入 `stability-recheck-interval`？ | 直接决定新文件到客户的时延上界与 NAS IO 开销 | 稳定 = **连续两次扫描** `path + size + mtime` 完全一致；对 24h 目录额外引入 `stability-recheck-interval`（默认 `5m`）做一次轻量复检，避免最长等待 24h | Scanner、时延指标 `discovered→stable`、`02-nfr.md` 的时延目标 | Phase 3（Scanner 实现前） | OPEN |
| **Q2** | `mtime` 的精度与取值口径（秒 / 纳秒 / 是否含时区）？`fingerprint` 的确切组成？ | `fingerprint` 参与 `UNIQUE(file_id, fingerprint)`，口径不一致会导致同一文件被反复识别为新版本（或漏检版本） | `fingerprint = SHA-256(relative_path + "\|" + size + "\|" + mtime_epoch_nanos)`；`mtime` 取 **UTC epoch 纳秒**，落库为 `BIGINT`；**不做内容 hash** | `file`、`file_version`、Scanner、去重约束 | Phase 2（DDL 前） | OPEN |
| **Q3** | Gateway 的认证方式、成功响应契约、幂等键、是否回传对象标识？ | 决定 `transfer_attempt.outcome`、重试判定、以及「结果未知」能否收敛 | 认证用 `AppId + AppKey`（Header 或签名）；成功 = HTTP 2xx 且响应体 `code=0`；幂等键 = `fileVersionId`；**是否回传 ETag/size 待定** | Gateway Worker、`transfer_attempt`、重复投递风险 R-02 | Phase 3（Gateway 实现前） | OPEN |
| **Q4** | manual retry 的 `WAITING_RETRY → READY` 会触发 S3 重传，是否接受？ | 人工重试若只重投 Gateway，可省一次 S3 上传；但状态机 T19 统一走 `READY` 会重传 | **接受重传**（简单、幂等、S3 覆盖语义天然安全）；V1 不为「只重投 Gateway」增加状态或分支 | `04-state-machine.md` T19、S3 带宽、人工重试耗时 | Phase 3（Manual Retry 实现前） | OPEN |
| **Q5** | 客户 SFTP 侧的文件名与目录结构规则？ | 决定 Gateway 侧落盘路径，以及是否需要在本系统做重命名 | 本系统**不负责**命名；S3 Object Key 与 Gateway 交付路径解耦，Gateway 按自身规则落盘 | `07-configuration-model.md`、Gateway 契约 | Phase 3（对接联调前） | OPEN |
| **Q6** | 是否需要上传后校验（size / ETag 比对）？ | 增加一次 `HeadObject` 可提高可信度，但增加时延与 S3 调用量 | **V1 不校验**：`PUT` 返回成功即认为上传成功（S3 强一致性读后写）；ETag 校验列入 V2 演进 | S3 上传、`05-exception-matrix.md` 的「上传成功但内容异常」场景 | Phase 3 | OPEN |

### 2.2 Q1 详细：扫描周期与稳定复检

- **问题**：已确认「稳定 = 连续两次扫描 `path + size + mtime` 完全一致」。若某目录 `scan-interval: 24h`，则一个文件最快也要约 24h 才能被判稳定，业务上可能不可接受。
- **候选方案**：

  | 方案 | 描述 | 优点 | 缺点 |
  |---|---|---|---|
  | ① 纯周期 | 只靠主扫描周期 | 实现最简单，IO 最小 | 24h 目录时延最坏 24h+ |
  | ② 引入 `stability-recheck-interval`（推荐默认） | 首次发现后 `T + interval` 做一次仅针对候选文件的轻量复检 | 时延可控（默认 5m 级） | 少量额外 IO；多一个配置项 |
  | ③ 缩短主扫描周期 | 把 24h 目录改成 1h | 简单 | 与已确认的「按客户约定扫描周期」冲突，IO 放大 |

- **当前默认**：方案 ②，`stability-recheck-interval` 默认 `5m`，仅对 `DISCOVERED` 候选生效；主扫描周期保持目录配置不变。
- **不确认的后果**：时延 SLA 无法写死；`02-nfr.md` 的 `discovered → stable` 指标无基线。
- **建议裁决时间**：Phase 1 结束前（配置模型冻结前）。

### 2.3 Q2 详细：fingerprint 口径

- **问题**：`fingerprint` 是「同一 NAS path 是否发生变化」的判定键，直接决定是否新建 `file_version`。
- **关键点**：
  - 必须**排除内容 hash**（已确认架构明确不计算 SHA-256），只用 `path + size + mtime`。
  - 必须明确 `mtime` 精度：SMB/NAS 在不同挂载参数下可能只到秒；若代码用纳秒而文件系统只到秒，取值仍需稳定。
  - 时区：一律转 UTC epoch，禁止使用本地时间字符串。
- **当前默认**：`fingerprint = SHA-256(relative_path + "|" + size + "|" + mtime_epoch_nanos)`；若实测 NAS 只提供秒精度，则退化为 `mtime_epoch_seconds`，并在 `07-configuration-model.md` 记录 `nas.mtime-precision: nanos|seconds`。
- **不确认的后果**：同一文件可能被反复识别为新版本（版本爆炸、无谓上传）或修改被漏检（版本丢失）。
- **建议裁决时间**：Phase 2（DDL 冻结前）。

### 2.4 Q3 详细：Gateway 契约

- **问题**：Gateway 是外部 B2B 系统，其认证、成功判定、幂等键决定了本系统「重试」与「结果未知」的处理。
- **需要向对方确认的清单**：
  1. 认证：`AppId`/`AppKey` 放置位置（Header / Query / 签名串）、是否需时间戳与 nonce 防重放。
  2. 成功契约：HTTP 状态码 + 业务码的组合；是否存在「已受理但未完成」的中间态。
  3. 幂等键：以 `fileVersionId` 为幂等键是否被支持；重复提交是否返回相同结果。
  4. 是否回传对象标识（ETag / size / 对象版本），用于与 S3 对账。
  5. 错误码语义：哪些错误码可重试（5xx、限流、连接失败），哪些不可重试（4xx 参数/权限）——**注意：即使「不可重试」，本系统仍按「无限重试直到成功」处理，仅调整退避与告警等级**。
- **当前默认**：HTTP 2xx + `code=0` 为成功；`fileVersionId` 为幂等键；超时/连接失败一律记为 `outcome = UNKNOWN` 并进入 `WAITING_RETRY`。
- **不确认的后果**：`transfer_attempt.outcome` 语义无法定稿；「重复投递」是否可接受无法与客户达成一致。
- **建议裁决时间**：Phase 3（Gateway Worker 实现前），最迟联调前。

### 2.5 Q4 详细：manual retry 与 S3 重传

- **问题**：T19 把 `WAITING_RETRY → READY`，`READY` 的语义是「待上传」，因此会重新执行 S3 上传。
- **分析**：S3 Object Key 固定为 `<customer-space>/<relative-path>`，重传是**幂等覆盖**，不会产生垃圾对象；代价是带宽与一次额外上传。
- **当前默认**：接受重传。若业务要求「只重投 Gateway」，则需要新增分支（例如 `S3_UPLOADED` 直接人工回退），**这会触及状态机，属于架构变更，必须单独确认**。
- **建议裁决时间**：Phase 3。

### 2.6 Q5 详细：客户侧文件名/目录结构

- **问题**：客户 SFTP 侧如何命名、如何分目录，本系统是否参与。
- **当前默认**：本系统只保证「对象内容正确、Key 规则正确」；客户侧命名与目录结构由 Gateway 与客户约定，本系统不感知、不重命名。
- **不确认的后果**：若客户要求本系统生成文件名，则 S3 Object Key 规则与 Gateway 交付路径需要重新设计（属于架构变更）。
- **建议裁决时间**：Phase 3 联调前。

### 2.7 Q6 详细：上传后校验

- **问题**：是否需要 `HeadObject` 比对 size / ETag。
- **当前默认**：V1 不校验。理由：S3 提供写后读强一致性，`PUT` 成功即落盘；校验会引入额外往返与失败分支。
- **若确认需要**：则应在 `UPLOADING → S3_UPLOADED` 迁移前增加一步校验，失败按 S3 异常处理（见 `05-exception-matrix.md`），不新增状态。
- **建议裁决时间**：Phase 3。

### 2.8 衍生运维问题（非架构问题，Phase 4+ 确认）

| 编号 | 问题 | 当前默认 | 状态 |
|---|---|---|---|
| Q7 | 测试库凭据（Docker 中 `localhost:5432` 的 postgres 容器）何时提供？ | 用户稍后提供；Phase 1 不使用真实连接 | OPEN |
| Q8 | 部署形态是 systemd 还是 Docker Compose？ | 两者都支持，凭据注入方式见 `10-security-model.md` | OPEN |
| Q9 | 监控栈是 Prometheus + Grafana 还是复用现有平台？ | 暴露 Prometheus 文本格式 `/actuator/prometheus` | OPEN |
| Q10 | 日志采集是文件还是 stdout？ | stdout（容器友好）+ 可选文件，见 `11-observability-model.md` | OPEN |

---

## 3. 核心问题：旧版本永久 Gateway 失败，如何保证最新版本不被阻塞

> 这是本系统最关键的业务承诺。本节给出**可直接实现**的判定规则与迁移路径；完整矩阵与论证见 `04-state-machine.md` §6，此处给出结论与索引。

### 3.1 问题本质

同一 NAS path 被修改会产生新版本。已确认「同一路径版本串行」：**同一 `file_id` 下，同时只能有一个版本处于执行阶段**。因此若 v1 永久投递失败（Gateway 侧持续拒绝/不可用），v2 会被 v1 挡在门外——除非有机制主动取消 v1 的执行权。该机制就是 **supersede（latest wins）**。

### 3.2 判定规则（三条守卫必须同时满足）

设 `T_old` 为同 `file_id` 下 `version_no` 更小的任务，`T_new` 为 `latest_stable(file)` 对应的任务：

| 守卫 | 条件 | 默认阈值 |
|---|---|---|
| ① 无活跃 lease | `worker_id IS NULL OR lease_until < now()` | `lease.duration = 5m` |
| ② 失败或等待越界 | `retry_count >= supersede.afterFailedAttempts` **或** `now() - COALESCE(last_attempt_finished_at, updated_at) >= supersede.afterWaiting` | `afterFailedAttempts = 5`、`afterWaiting = 30m` |
| ③ 宽限期已过 | `now() - COALESCE(last_attempt_finished_at, updated_at) >= supersede.gracePeriod` | `gracePeriod = max(15m, gateway.readTimeout + lease.duration + 5m)` |

三条同时满足 → `T_old` 迁移 **T20 / T21 → `CANCELLED`**，落库：

```text
status                    = CANCELLED
cancel_reason             = SUPERSEDED_BY_NEWER_VERSION
superseded_by_version_no  = <latest_stable 的 version_no>
cancelled_at              = now()
transfer_event(operator='system', event_type='TASK_SUPERSEDED')
```

### 3.3 哪些状态可被 supersede

| 状态 | 可被 supersede | 说明 |
|---|---|---|
| `DISCOVERED` | ✅ | 未执行、无在途请求；仍须满足三条守卫（守卫自 `updated_at` 起算） |
| `STABILITY_CHECK` | ✅ | 未执行、无在途请求；仍须满足三条守卫（守卫自 `updated_at` 起算） |
| `READY` | ✅ | 未执行、无在途请求；仍须满足三条守卫（守卫自 `updated_at` 起算） |
| `WAITING_RETRY` | ✅（须满足三条守卫） | **核心场景**：旧版本长期失败 |
| `UPLOADING` | ❌（活跃 lease 期间） | 等 lease 过期 → T10（→`READY`）→ 再评估 |
| `S3_UPLOADED` | ❌（活跃 lease 期间） | 等 lease 过期 → T12（→`GATEWAY_DELIVERING` 继续投递）；不在本状态 supersede |
| `GATEWAY_DELIVERING` | ❌（活跃 lease 期间） | **最关键**：请求在途，绝不能被覆盖竞态打断 |
| `DELIVERED` | ❌ **永不** | 终态与历史事实 |
| `CANCELLED` | ❌ | 已是终态 |

### 3.4 为什么「一定」不会永久阻塞（上界论证）

```text
T_worst = lease.duration                     (旧版本活跃执行最长占用)
        + max(supersede.afterWaiting,        (按时间触发)
              afterFailedAttempts × 退避上界) (按次数触发；退避上界 = maxDelay = 1h)
        + 一个 SupersedeService 调度周期
```

代入默认值：`5m + max(30m, 5 × 1h = 5h) + 调度周期` ≈ **5~6 小时**，远小于「永久」。

三条关键性质：

1. **`retry_count` 单调递增**：只要旧版本持续失败，`retry_count` 必然越过 `afterFailedAttempts`（按次数触发路径）。
2. **退避有上界**：指数退避受 `maxDelay = 1h` 限制，`afterWaiting = 30m` 提供按时间触发的第二条路径。
3. **lease 有上界**：即使旧版本正在执行，`lease_until` 有限；JVM 崩溃或长任务都会让 lease 过期，走 T10/T16 后回到可 supersede 的状态。

### 3.5 对 S3 覆盖语义的影响

| 场景 | 结论 |
|---|---|
| 旧版本**已上传** S3 后被 supersede | 其对象与新版是同一 Key，**无需清理**：新版上传会覆盖它 |
| 旧版本**未上传**即被 supersede | 不产生任何 S3 对象 |
| 旧版本被 supersede 后客户是否看到该历史版本 | **不保证**（这是业务明确允许的：当前版本可靠投递优先于历史版本全投递） |
| S3 最终内容 | 等于**最后一次成功上传**的版本；`DELIVERED` 记录表明「该版本曾被成功投递」 |
| 残余竞态（A1） | Gateway 在途请求 vs 新版覆盖：靠「活跃 lease 禁止 supersede」+「宽限期 ≥ readTimeout + lease + 5m」把窗口压到 `readTimeout` 之外；At-least-once 下的重复投递已被业务接受 |

### 3.6 与 manual retry 的交互

| 情形 | 处理 |
|---|---|
| 该版本仍是 `latest_stable` 且状态 `WAITING_RETRY` | 允许 T19 → `READY` |
| 该版本已不是 `latest_stable` | **拒绝**，错误码 `SUPERSEDED_BY_NEWER_VERSION`（仍写 operator 审计） |
| 该版本已 `CANCELLED` | 拒绝（终态不可复活，V1 行为） |
| 该版本已 `DELIVERED` | 拒绝（重复投递需业务单独确认） |
| 该任务有活跃 lease | 拒绝（避免与正在执行的 worker 冲突） |

**结论**：manual retry 不会绕过 latest wins，也不会「复活」被 supersede 的老版本。

---

## 4. 风险登记表

> 等级 = 概率 × 影响 的定性合成：**高 / 中 / 低**。「缓解后等级」是落实缓解措施后的残余等级。

| 编号 | 风险 | 类别 | 概率 | 影响 | 等级 | 缓解措施 | 缓解后 | 责任阶段 |
|---|---|---|---|---|---|---|---|---|
| **R-01** | 文件永久未到达客户（业务最严重） | 业务 | 低 | 高 | **高** | 无 `FAILED` 状态；失败恒为 `WAITING_RETRY` 无限重试；`max_retry_count` 只作长期重试阈值；失败任务永不删除；`LongPendingTask` 告警（6h P2 / 24h P1） | 中 | Phase 3/4 |
| **R-02** | Gateway 重复投递（At-least-once 语义） | 一致性 | 中 | 中 | 中 | 以 `fileVersionId` 作幂等键（Q3）；`attempt_no` 记录每次尝试；客户侧需容忍重复 | 中 | Phase 3 |
| **R-03** | S3 覆盖竞态导致客户收到旧内容或乱序 | 一致性 | 低 | 中 | 中 | 活跃 lease 期间禁止 supersede；宽限期 ≥ `readTimeout + lease + 5m`；同路径串行守卫 | 低 | Phase 3 |
| **R-04** | 旧版本永久失败阻塞最新版本 | 业务 | 中 | 高 | **高** | supersede（§3）：三条守卫 + 5~6h 上界；`TASK_SUPERSEDED` 审计与告警 | 低 | Phase 3 |
| **R-05** | NAS 挂载抖动 / 不可读导致漏扫或误判删除 | 数据 | 中 | 高 | **高** | 单次 IO 失败不判删除；`file_exists` 只在成功列目录后更新；`scanner_scan_failure_total` 连续 ≥3 次告警 | 中 | Phase 3 |
| **R-06** | 文件仍在写入时被判「稳定」 | 数据 | 中 | 中 | 中 | 稳定 = 连续两次 `path+size+mtime` 一致；24h 目录加 `stability-recheck-interval`（Q1） | 低 | Phase 3 |
| **R-07** | PostgreSQL 单点故障导致全系统停摆 | 可用性 | 低 | 高 | 中 | V1 接受单点；DB 不可用时不 claim、不误标状态；依赖备份/PITR（Phase 2+ 确认） | 中 | Phase 2/4 |
| **R-08** | 密钥泄露（S3 / Gateway / DB） | 安全 | 低 | 高 | **高** | 环境变量/Secret 注入；禁止入 PostgreSQL、禁止入 Git、禁止入日志；`logging.mask-sensitive: true`；轮换流程见 `10-security-model.md` | 低 | Phase 1/4 |
| **R-09** | 僵尸 lease 长期占用任务 | 可用性 | 中 | 中 | 中 | lease 独立于业务状态；`stale lease` 恢复（T10/T12/T16）；`stale_lease_total` 告警 | 低 | Phase 3 |
| **R-10** | 数据保留清理误删在途任务 | 数据 | 低 | 高 | 中 | 只清理终态（`DELIVERED`/`CANCELLED`）且超 1 年；非终态永不清理（A7）；清理 SQL 强制状态过滤 | 低 | Phase 2/3 |
| **R-11** | 并发失控导致 NAS / S3 / Gateway 过载 | 性能 | 中 | 中 | 中 | 全局 + 目录级并发上限；有界 executor + 背压；Virtual Thread 也必须用 `Semaphore` 限流；禁止无界队列 | 低 | Phase 3 |
| **R-12** | 扫描周期长导致新文件交付时延不可接受 | 性能 | 中 | 中 | 中 | Q1 的 `stability-recheck-interval`；`discovered→stable`、`stable→delivered` 指标与看板 | 中 | Phase 3 |
| **R-13** | `mtime` 精度/时区不一致导致版本漂移 | 数据 | 中 | 中 | 中 | Q2 明确口径；`nas.mtime-precision` 配置；统一 UTC epoch | 低 | Phase 2 |
| **R-14** | Gateway 契约未定导致实现返工 | 项目 | 中 | 中 | 中 | Q3 清单在 Phase 3 前冻结；`transfer_attempt.outcome` 先按 `SUCCESS/FAILURE/UNKNOWN` 设计（A4） | 中 | Phase 3 |
| **R-15** | 指标/日志基数爆炸（如按 `filePath` 打标签） | 运维 | 中 | 中 | 中 | 指标标签只用低基数（`customer_space`、`directory_id`、`error_type`）；`filePath` 只进结构化日志，不进指标标签 | 低 | Phase 3/4 |
| **R-16** | 同 path 版本过多导致 S3 覆盖频繁、客户看到频繁变更 | 业务 | 低 | 低 | 低 | 稳定检测已过滤写入中文件；S3 覆盖是幂等设计；如需限制可后续引入节流（不在 V1） | 低 | V2 |
| **R-17** | 日志缺失关键字段导致无法定位「文件为何未到客户」 | 运维 | 低 | 中 | 低 | 结构化日志强制 `taskId`/`fileVersionId`/`fileId`/`directoryId`/`customerSpace`/`filePath`；启动期校验日志格式；查询配方见 `11-observability-model.md` | 低 | Phase 3 |

---

## 5. 【架构问题】A1–A8

> 以下条目全部**保持原设计不变**，仅登记冲突/缺口，等待用户确认后再统一落地。每条格式：**问题 / 风险 / 建议方案 / 对现有设计的影响**。

### 【架构问题】A1：Gateway 在途请求与 S3 覆盖的竞态无法完全消除

- **问题**：v1 处于 `GATEWAY_DELIVERING`（Gateway 正在读取 S3 对象）时，若 v2 上传覆盖同一 Object Key，Gateway 可能读到混合内容或读到 v2 内容却按 v1 记账。
- **风险**：客户可能先收到 v2 内容，或收到一次「内容与版本不匹配」的投递；At-least-once 下还可能重复投递。
- **建议方案**：靠两条规则把窗口收敛到 `readTimeout` 之外——① 活跃 lease 期间**禁止** supersede；② 宽限期 `gracePeriod ≥ gateway.readTimeout + lease.duration + 5m`。**不引入** S3 对象版本控制（已明确排除）。若 Gateway 支持，可在投递请求中携带对象标识（ETag/size）由其校验。
- **对现有设计的影响**：**无**。宽限期只是配置值，不改变状态集合、迁移矩阵或 S3 Key 规则。残余风险被业务接受（At-least-once）。

### 【架构问题】A2：`file_version.version_no` 在 §39 建议但 §33 DDL 缺失

- **问题**：串行守卫与 supersede 判定都依赖 `version_no` 的确定性排序；§33 的 `file_version` 表定义没有该列，§39 才建议增加。
- **风险**：若靠 `id` 隐含排序，语义脆弱（并发插入、数据清理后重建都会打乱）。
- **建议方案**：在 `file_version` 增加 `version_no BIGINT NOT NULL` + `UNIQUE(file_id, version_no)`；由 Scanner 在同一事务内按 `max(version_no) + 1` 分配。
- **对现有设计的影响**：Phase 2 DDL 增加一列一约束；状态机与 supersede 规则不变。

### 【架构问题】A3：`transfer_task` 缺 `file_id` / `version_no`，但 §38 claim SQL 依赖

- **问题**：§38 的同路径串行 claim SQL 使用 `previous.file_id`，但 §34 的 `transfer_task` DDL 没有 `file_id`，只能 join `file_version`。
- **风险**：每次 claim 都要 join，且缺少 `(file_id, version_no)` 索引时随数据增长变慢；串行守卫的正确性也依赖 join 不写错。
- **建议方案**：在 `transfer_task` 冗余 `file_id` 与 `version_no`（创建任务时写入、之后不可变），并建 `(file_id, version_no)` 索引。
- **对现有设计的影响**：Phase 2 DDL 增两列一索引；状态机不变。冗余列的一致性由「任务创建后不可变」保证。

### 【架构问题】A4：`transfer_attempt.success BOOLEAN` 无法表达「结果未知」

- **问题**：§29/§35 用 `success BOOLEAN` 记录尝试结果，但 Gateway 超时/响应丢失时结果是**未知**（可能已成功），既不是 true 也不是 false。
- **风险**：把「未知」记为失败会低估成功数、误导告警；记为成功则可能掩盖真实失败。
- **建议方案**：增加 `outcome` 枚举列（`SUCCESS` / `FAILURE` / `UNKNOWN`）替代或补充 `success`；`UNKNOWN` 按「允许重复投递」处理（不新增业务状态）。
- **对现有设计的影响**：Phase 2 DDL 增加一列（或改列语义）；**状态集合不变**，`UNKNOWN` 不进入状态机，只影响 attempt 记录与告警。

### 【架构问题】A5：`file_version.status` 与 `transfer_task.status` 双状态并存

- **问题**：§33 给 `file_version` 定义了 `status`，§34 给 `transfer_task` 也定义了 `status`，两者取值同一套状态名，事实来源不清。
- **风险**：两处状态可能不一致（如 `file_version.status = READY` 而 task 已 `DELIVERED`），运维与查询会得到矛盾答案。
- **建议方案**：明确 `transfer_task.status` 是**唯一权威任务状态**；`file_version.status` 只表达发现/稳定语义（`DISCOVERED` / `STABILITY_CHECK` / `READY` / `CANCELLED`），或在 Phase 2 直接移除该列、改由 `stable_at IS NOT NULL` 与 task 状态派生。
- **对现有设计的影响**：Phase 2 需确认列语义；状态机本身不变。查询配方（`11-observability-model.md`）以 `transfer_task.status` 为准。

### 【架构问题】A6：`directory.path` 与 `relative path` 的关系未定义

- **问题**：§5 配置有 `mount-path` 与目录相对路径，§31 的 `directory` 表有 `path`，但「绝对路径 = mount_path + path」的拼接规则（分隔符、前导斜杠、末尾斜杠、大小写）没有定义。
- **风险**：不同实现拼出的绝对路径不一致，导致扫描漏文件或 `UNIQUE(nas_id, path)` 出现重复目录。
- **建议方案**：明确定义：`path` 为相对挂载点的 POSIX 风格路径，**不带前导/末尾斜杠**，统一 `/` 分隔；绝对路径 = `mount_path` + `/` + `path`；Scanner 只接受规范化后的路径。
- **对现有设计的影响**：Phase 2/3 增加规范化函数与配置校验；状态机、S3 Key 规则不变。

### 【架构问题】A7：1 年保留策略与「失败任务永不删除」冲突

- **问题**：§54 要求业务数据保留 1 年，§50 要求失败任务永不删除；若失败任务一直处于 `WAITING_RETRY`，两条规则直接冲突。
- **风险**：按 1 年清理会删掉仍在重试的任务（等于丢文件，R-01）；永不删除又会让表无限增长。
- **建议方案**：**裁决为「仅清理终态且超 1 年；非终态永不清理」**。即 `DELIVERED` / `CANCELLED` 且超 1 年可清；`WAITING_RETRY` 等非终态永不清理。长期堆积的非终态任务通过告警（R-01）暴露，而不是靠删除掩盖。
- **对现有设计的影响**：清理作业需强制 `status IN ('DELIVERED','CANCELLED')` 条件；`06-data-model.md` §6 已按此裁决编写。**状态集合不变**。

### 【架构问题】A8：未来多实例下扫描调度缺 DB 级互斥（低）

- **问题**：V1 是单机，扫描由进程内调度器触发；§80 的 V5 规划水平扩展后，多实例会重复扫描同一目录。
- **风险**：重复扫描会重复触发稳定检测与任务创建（唯一约束可兜住数据重复，但浪费 IO 并放大日志）。
- **建议方案**：V1 **只留扩展点**，不实现分布式锁；V5 时引入 DB 级互斥（如 `SELECT ... FOR UPDATE SKIP LOCKED` 抢占目录扫描权或 `advisory lock`）。
- **对现有设计的影响**：**无**。V1 不引入分布式锁（已明确排除）；仅在设计上保证扫描动作幂等（唯一约束兜底）。

### 5.9 各分册登记的【架构问题】索引

除基线权威的 A1–A8 外，各分册在展开过程中还登记了**局部**的 `【架构问题】`。它们不是新架构，而是同一约束在不同领域的细化或新增的低风险缺口；此处集中索引，确保「所有与已确认架构冲突或需要变更的点」都在本文件可查。

| 编号 | 所在文档 | 摘要 | 与 A1–A8 / 风险的关系 |
|---|---|---|---|
| `NFR-A1` | `02-nfr.md` | 长期依赖故障下的「重试风暴」 | 补充 R-01、R-11 |
| `NFR-A2` | `02-nfr.md` | `supersede.gracePeriod` 默认值与计算公式不自洽 | 补充 A1 |
| `NFR-A3` | `02-nfr.md` | 24h 扫描周期目录的稳定确认延迟达 48h | 对应 Q1、R-12 |
| `EX-A1` | `05-exception-matrix.md` | `transfer_attempt` 无法表达「结果未知」 | 同 A4 |
| `EX-A2` | `05-exception-matrix.md` | 永久性错误与「禁止 FAILED、永久 WAITING_RETRY」冲突 | 补充 R-01 |
| `EX-A3` | `05-exception-matrix.md` | 429 限流与固定指数退避缺乏联动 | 补充 R-11 |
| `C1` | `08-concurrency-model.md` | 串行守卫是读谓词，缺少 `file_id` 维度的显式串行化原语 | 同 A2 / A3 |
| `C2` | `08-concurrency-model.md` | 目录级并发配额无法在 claim SQL 中表达 | 新增（低） |
| `C3` | `08-concurrency-model.md` | claim 即迁移 `UPLOADING`（T6）与「先 claim 后执行」存在 lease 空转窗口 | 补充 A3 |
| `C4` | `08-concurrency-model.md` | 目录并发配置的命名与位置不一致 | 补充 A6 |
| `C5` | `08-concurrency-model.md` | 上传与投递两个 `Semaphore` 的获取/释放顺序未定义 | 新增（低） |
| `CR-1` | `09-crash-recovery-model.md` | 启动顺序中「配置校验」与「DB 连接」的相对位置不一致 | 新增（低） |
| `CR-2` | `09-crash-recovery-model.md` | 恢复判定强依赖 `transfer_task.s3_uploaded_at`（基线中仍为「建议列」） | 补充 A3 |
| `CR-3` | `09-crash-recovery-model.md` | `S3_UPLOADED` + `s3_uploaded_at IS NULL` 组合无对应恢复迁移 | 新增 |
| `O1` | `11-observability-model.md` | 指标标签与日志字段的边界 | 补充 R-15 |
| `O2` | `11-observability-model.md` | §58 指标命名与状态集合的映射 | 新增（低） |
| `O3` | `11-observability-model.md` | 运维查询配方依赖 A2/A3 未决列 | 同 A2 / A3 |
| `O4` | `11-observability-model.md` | `transfer_attempt.outcome` 未决 | 同 A4 |
| `O5` | `11-observability-model.md` | 依赖健康是否参与 readiness 摘流 | 新增（低） |
| `O6` | `11-observability-model.md` | `filePath` 语义依赖 A6 | 同 A6 |
| `O7` | `11-observability-model.md` | `file_version.status` 与 `transfer_task.status` 双状态口径 | 同 A5 |
| `O8` | `11-observability-model.md` | 死寂告警的业务时段基线 | 新增（低） |
| `AC-1` | `12-acceptance-criteria.md` | Gateway「明确成功」契约未定义，导致 T13 相关 AC 不可客观判定 | 同 Q3 |
| `AC-2` | `12-acceptance-criteria.md` | 恢复/可观测指标命名在 `09` 与 `11` 中不一致 | 新增 |
| `AC-3` | `12-acceptance-criteria.md` | v1 Gateway 读到 v2 S3 对象无法绝对消除 | 同 A1 |
| （无编号） | `10-security-model.md` | SMB 安全基线未定义 | 新增（低） |
| （无编号） | `10-security-model.md` | V1 manual retry 的鉴权入口未定义（`SEC-28`） | 对应 Q4 |
| （无编号） | `10-security-model.md` | 保留策略与「失败任务永不删除」冲突 | 同 A7 |

> 处理原则：以上条目**同样保持原设计不变**，只登记。`AC-2`（指标命名不一致）与 `C4`（配置命名不一致）属于**文档内部一致性**问题，应在 Phase 1 冻结命名时一并消除，不涉及架构变更。

---

## 6. 变更处理流程（Phase 0 之后）

1. 用户对某条 Q/A 给出结论 → 在本文件把状态改为 `ACCEPTED` / `CHANGED` / `DEFERRED`。
2. 若为 `CHANGED`（修改已确认架构）：同步更新受影响的 `03`–`12` 文档、基线文件，并在 `00-index.md` 的映射表中登记。
3. 若为 `ACCEPTED`（按默认假设）：把默认值写入对应文档的「配置项总表」或「不变量」章节。
4. 进入 Phase 1 前，本文件**不允许存在会改变数据模型或状态机的 OPEN 项**（当前 Q2、Q3 属于此列，须在对应阶段前收敛）。

## 7. 与验收标准的关系

| 验收维度 | 相关风险 | 相关开放问题 |
|---|---|---|
| 可靠性 | R-01、R-04、R-09、R-11 | Q1、Q4 |
| 一致性 | R-02、R-03、R-13 | Q2、Q3 |
| 可恢复 | R-05、R-09 | — |
| 可追踪 | R-17 | — |
| 可运维 | R-12、R-15、R-17 | Q9、Q10 |

逐条可执行验收标准见 `12-acceptance-criteria.md`。

## 8. 结论

- **核心承诺已给出可实现的答案**：旧版本永久失败不会阻塞最新版本——由 supersede 的三条守卫 + 5~6 小时上界保证（§3）。
- **8 条【架构问题】全部登记、全部保持原设计不变**：A2/A3/A4/A5/A6/A7 需在 Phase 2 的 DDL 中统一裁决；A1 由配置宽限期收敛；A8 属 V5 演进。
- **6 条开放问题 + 4 条运维问题待用户确认**：Q2、Q3 影响数据模型与 attempt 语义，须在 Phase 2/3 前收敛；其余可按默认假设推进。
- **风险登记表 17 条**：最高等级的 R-01（文件永久未达）、R-04（旧版本阻塞）、R-05（NAS 误判）、R-08（密钥泄露）均已给出缓解并降至中/低。
