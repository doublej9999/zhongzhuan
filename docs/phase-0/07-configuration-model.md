# 07 Configuration Model（配置模型）

> 本文档是 Phase 0 交付物之一，权威事实源为 `phase0-baseline.md`（下称"基线"），
> 已确认架构为 workspace 根目录 `NAS → S3 → B2B Gateway → SFTP 中转系统整体技术设计.md`（下称"整体设计"）。
> 若本文与两者冲突，以基线为准；冲突处一律用 `【架构问题】` 标注，**不擅自修改架构**。
> 本文所有密钥/凭据均为占位符，**不含任何真实密钥**。

---

## 本部分范围

1. 定义 V1（单机、单 JVM）的**唯一业务配置文件** `application.yml` 的完整配置树与每个配置项语义。
2. 定义**敏感信息注入模型**：哪些配置只能来自环境变量 / Secret，禁止进入 PostgreSQL 与 yml 明文。
3. 定义**配置快照（config_version）**：任务创建时固化哪些配置，配置变更后旧任务如何继续执行。
4. 定义**启动期配置校验（fail-fast）**规则清单，以及校验失败时的行为。
5. 给出**配置项总表**（默认值 / 取值范围 / 是否敏感 / 是否进任务快照 / 影响模块 / 变更生效方式）。
6. 标注与已确认架构的不一致点（尤其 `【架构问题】A6`）。

## 本部分不做什么

1. 不写业务代码，不创建 `*.java`、`*.sql`、`pom.xml`、`mvnw`、`application.yml` 等工程文件；本文的 yml 仅为**示意配置**。
2. 不定义数据库 DDL（见数据模型文档），不定义状态机（见状态机文档）。
3. 不引入配置中心（Nacos / Apollo / Consul）、不做配置热更新、不做 Admin 前端（整体设计 §79 明确不引入）。
4. 不定义 Gateway 认证协议细节与成功响应契约（开放问题 Q3）。
5. 不给出真实密钥、不给出生产主机名/IP/库名/密码。
6. 不改变已确认架构的模块划分与状态集合。

## 对应整体设计文档章节

| 整体设计章节 | 本文对应小节 |
|---|---|
| §5 配置设计（yml 放业务配置、环境变量放敏感配置） | §2、§4 |
| §6 配置快照（`transfer_task` 固化 `directory_id`/`customer_space`/`s3_bucket`/`s3_object_key`/`gateway_route`/`config_version`） | §5 |
| §16 Lease（`leaseDuration`，默认 5 分钟） | §2、§6 |
| §25 Retry（retry 不占 worker，`WAITING_RETRY`） | §2、§7 |
| §26 Exponential Backoff（`initialDelay`/`multiplier`/`maxDelay`/`jitter`） | §2、§6 |
| §43 并发控制（`globalUploadConcurrency` / `globalGatewayConcurrency` / `directory.maxConcurrency`） | §2、§3、§6 |
| §54 数据保留（1 年，按 FK 顺序清理） | §2、§6 |
| §56 敏感信息（PostgreSQL 不存 Secret Key / AppKey） | §4 |
| §78 配置修改（V1 改 yml 后 restart，旧任务用快照） | §5、§7 |

---

## 1. 配置分层原则

| 层 | 存放位置 | 内容 | 是否进 DB |
|---|---|---|---|
| L1 敏感凭据 | 环境变量 / Secret | `S3_ACCESS_KEY`、`S3_SECRET_KEY`、`GATEWAY_APP_ID`、`GATEWAY_APP_KEY`、`DB_APP_PASSWORD` | **否**（基线 §4、整体设计 §56） |
| L2 业务配置 | `application.yml` | 目录清单、扩展名、扫描周期、并发、重试、lease、supersede、保留 | 部分冗余到 `directory` 表，见 §5 |
| L3 运行时状态 | PostgreSQL | 任务状态、lease、attempt、事件 | 是（唯一 Source of Truth） |
| L4 任务级快照 | PostgreSQL `transfer_task` | 创建任务时固化的配置子集 | 是（整体设计 §6） |

**核心原则**：yml 是**启动期输入**，PostgreSQL 是**运行期事实来源**，任务快照是**执行期冻结视图**。
三者一旦确定，执行路径只读快照，不再回读 yml。

---

## 2. application.yml 完整配置树

> 示意配置，非可运行文件。仅表达配置项名称、层级、默认值与约束关系。
> 键名使用 Spring Boot 宽松绑定风格（kebab-case）；`${...}` 为环境变量占位符。

```yaml
# =========================================================
# 示意配置，非可运行文件
# =========================================================

app:
  instance-id: nas-s3-gateway-01        # 单机唯一实例标识
  worker-id-prefix: worker              # worker_id 前缀，形如 worker-01
  time-zone: Asia/Shanghai              # 全局时区，影响 mtime 解析与日志时间

spring:
  application:
    name: nas-s3-gateway-transfer
  datasource:
    url: ${DB_URL}
    username: ${DB_APP_USERNAME}
    password: ${DB_APP_PASSWORD}        # 仅环境变量注入，禁止明文
    hikari:
      maximum-pool-size: 20             # 连接池上限
      minimum-idle: 5
      connection-timeout: 10s           # 获取连接超时
      validation-timeout: 5s
      idle-timeout: 10m
      max-lifetime: 30m
      leak-detection-threshold: 60s
  task:
    scheduling:
      pool:
        size: 4                         # 调度线程池（Scanner/Recovery/Retention 各占 1）

nas:
  directories:                          # 每个 directory 独立配置（基线 §4.1：customer_space 1-N directory）
    - id: customer-a-order
      nas-id: nas-01                    # 逻辑 NAS 编号，对应 nas.name / nas.mount_path
      mount-path: /mnt/nas01            # 操作系统挂载点（SMB/CIFS 已挂载）
      relative-path: customer-a/order   # 或 path，见【架构问题】A6
      customer-space: customer-a        # 必须已存在于 customer_space 表
      extensions: [".zip", ".xml"]      # 必须带点，非空
      scan-interval: 6h
      max-concurrency: 4                # 目录级 Semaphore（基线 §6 建议 2~4）
      enabled: true
      stability-recheck-interval: 5m    # 稳定复检间隔（开放问题 Q1，默认 5m）

    - id: customer-a-invoice
      nas-id: nas-01
      mount-path: /mnt/nas01
      relative-path: customer-a/invoice
      customer-space: customer-a
      extensions: [".csv"]
      scan-interval: 24h
      max-concurrency: 2
      enabled: true
      stability-recheck-interval: 5m

    - id: customer-b-report
      nas-id: nas-02
      mount-path: /mnt/nas02
      relative-path: customer-b/report
      customer-space: customer-b
      extensions: [".zip"]
      scan-interval: 6h
      max-concurrency: 4
      enabled: true
      stability-recheck-interval: 5m

s3:
  endpoint: ${S3_ENDPOINT}              # 如 https://s3.example.internal（占位）
  region: ${S3_REGION}                  # 如 cn-north-1（占位）
  bucket: ${S3_BUCKET}                  # 业务 bucket 名（占位）
  access-key: ${S3_ACCESS_KEY}          # 仅环境变量
  secret-key: ${S3_SECRET_KEY}          # 仅环境变量
  path-style-access: true               # 自建/兼容 S3 一般需要 path style
  connect-timeout: 10s
  socket-timeout: 60s
  max-retries: 3                        # SDK 层重试；与业务 retry 独立

gateway:
  endpoint: ${GATEWAY_ENDPOINT}         # B2B Gateway HTTP API 基址（占位）
  app-id: ${GATEWAY_APP_ID}             # 仅环境变量
  app-key: ${GATEWAY_APP_KEY}           # 仅环境变量
  connect-timeout: 10s
  read-timeout: 60s                     # 与 supersede.grace-period / lease.duration 有约束关系
  max-inflight: 8                       # 在途请求上限，与 worker.gateway.max-concurrency 对齐

worker:
  upload:
    max-concurrency: 8                  # 全局上传并发 Semaphore（整体设计 §43）
  gateway:
    max-concurrency: 8                  # 全局 Gateway 并发 Semaphore（整体设计 §43）
  claim-batch-size: 16                  # 单次 claim 条数上限（FOR UPDATE SKIP LOCKED LIMIT n）
  queue-capacity: 16                    # 有界队列容量 = 2 × 单阶段 max-concurrency；满则停止 claim（背压）
  use-virtual-threads: true             # 允许，但必须叠加 Semaphore，禁止无界并发

retry:
  initial-delay: 30s                    # 整体设计 §26
  multiplier: 2                         # 必须 >= 1
  max-delay: 1h
  jitter: 20%
  max-retry-count: 20
  # 注意：max-retry-count 仅作为"进入长期重试区间"的阈值（用于告警与 supersede 判定），
  #       **不是永久停止**。基线 §1 / §7：超过次数后任务仍为 WAITING_RETRY，
  #       永久重试直到成功；禁止引入 FAILED 终态。

lease:
  duration: 5m                          # 整体设计 §16：leaseDuration
  renew-interval: 1m40s                 # 建议 duration/3，执行中周期性续租
  stale-scan-interval: 1m               # Recovery 扫描过期 lease 的周期

supersede:
  enabled: true                         # 基线 §3.3：周期执行 + claim 前校验
  grace-period: 15m                     # 必须 >= gateway.read-timeout + lease.duration + 5m
  after-failed-attempts: 5              # retry_count 达到该值即满足 supersede 条件之一
  after-waiting: 30m                    # 或等待时长达到该值

scanner:
  thread-pool-size: 4                   # 目录扫描线程池（每个目录一个扫描任务）
  max-files-per-scan: 10000             # 单目录单轮最大文件数（基线 §0：单目录最多 1 万文件）
  follow-symlinks: false                # 默认不跟随软链，避免重复/环路
  failure-alert-threshold: 3            # 连续扫描失败次数阈值，触发告警（不停止扫描）

retention:
  enabled: true
  keep-days: 365                        # 1 年（整体设计 §54）
  cleanup-cron: "0 30 3 * * *"          # 每天 03:30 执行
  only-terminal-states: true            # 仅 DELIVERED / CANCELLED 可清理（基线 §8）

logging:
  level: INFO                           # 可设 DEBUG 临时排障
  format: json                          # 结构化 JSON，便于 Loki/ELK 采集
  mask-sensitive: true                  # 对密钥、Authorization、AppKey 做脱敏

management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  endpoint:
    health:
      show-details: when-authorized
    metrics:
      enabled: true
    prometheus:
      enabled: true
  metrics:
    tags:
      application: nas-s3-gateway-transfer
      instance: ${app.instance-id}
      nas: nas-01
```

### 2.1 关键约束关系（跨配置项）

| 约束 | 表达式 | 依据 |
|---|---|---|
| lease 必须长于 Gateway 读超时 | `lease.duration > gateway.read-timeout` | 避免 Gateway 在途时 lease 过期被恢复抢走 |
| 宽限期覆盖在途请求 | `supersede.grace-period >= gateway.read-timeout + lease.duration + 5m` | 基线 §3.5 关闭 S3 覆盖竞态 |
| 有效并发 | `min(worker.<stage>.max-concurrency, directory.max-concurrency)` | 基线 §6、整体设计 §43 |
| 在途上限不超并发 | `gateway.max-inflight >= worker.gateway.max-concurrency` | 避免 semaphore 与 HTTP 池互相饥饿 |
| 队列容量有界 | `worker.queue-capacity > 0` | 基线 §6 禁止无界队列 |

---

## 3. NAS 目录级配置（每个 directory 独立）

基线 §4.1 的关系是 `customer_space 1─N directory`、`nas 1─N directory`，
因此**目录是配置的最小业务单元**，所有与业务目标相关的配置都必须挂在单个 directory 上，
不允许"全局客户空间"这类隐式继承。

| 配置项 | 语义 | 是否进任务快照 |
|---|---|---|
| `id` | 目录逻辑标识（运维可读，如 `customer-a-order`） | 是（用于审计与日志 `directoryId`） |
| `nas-id` | 逻辑 NAS 编号，映射 `nas.name` / `nas.mount_path` | 是 |
| `mount-path` | 操作系统挂载点，如 `/mnt/nas01` | 是（用于拼绝对路径） |
| `relative-path`（或 `path`） | 目录相对路径，与 `mount-path` 的拼接规则见 A6 | 是（`directory_id` + `path` 唯一约束） |
| `customer-space` | 目标客户空间 code，必须在 `customer_space` 表存在 | 是（决定 S3 key 前缀与 Gateway 路由） |
| `extensions[]`（后缀白名单，等价键名 `suffixes` / `suffix`） | 后缀白名单，必须带点、非空 | 是（决定发现哪些文件） |
| `scan-interval` | 扫描周期，如 `6h` / `24h` | 否（下次扫描即用新值） |
| `max-concurrency` | 目录级并发上限 | 否（影响新 claim，不影响已执行任务） |
| `enabled` | 目录开关；`false` 后不再发现新文件，且按基线 T5/T7 取消未开始读取的任务 | 否 |
| `stability-recheck-interval` | 稳定复检间隔（开放问题 Q1） | 否 |

**目录禁用语义**：`enabled: false` 不会删除已有任务，也不会取消正在执行的任务；
仅对尚未开始读取 NAS 的任务按基线 T5/T7 走 `CANCELLED`。

---

## 4. 敏感信息注入模型

### 4.1 硬性规则

1. **S3 AccessKey / SecretKey、Gateway AppId / AppKey、数据库密码**：
   只能通过**环境变量或外部 Secret** 注入，**禁止**明文写入 `application.yml`、**禁止**写入 PostgreSQL
   （基线 §4 / 整体设计 §56：PostgreSQL 不保存 `S3 Secret Key`、`Gateway AppKey`）。
2. yml 中一律使用占位符写法 `${ENV_VAR_NAME}`，**不得**给出默认值（如 `${S3_SECRET_KEY:abc}` 是禁止的）。
3. `transfer_task` 的配置快照**只固化非敏感字段**（`s3_bucket` / `s3_object_key` / `gateway_route` /
   `directory_id` / `customer_space` / `config_version`），**绝不固化任何密钥**。
4. 日志中一律脱敏：`logging.mask-sensitive: true`，禁止打印 `Authorization`、`AppKey`、`SecretKey`。
5. 禁止在文档、示例、注释、测试资源中出现真实密钥；本文全部为占位符。

### 4.2 环境变量对照表

| # | 环境变量 | 对应 yml 键 | 用途 | 是否必填 | 注入方式 |
|---|---|---|---|---|---|
| 1 | `S3_ENDPOINT` | `s3.endpoint` | S3 兼容端点地址 | 是 | systemd / Docker env |
| 2 | `S3_REGION` | `s3.region` | S3 区域 | 是 | systemd / Docker env |
| 3 | `S3_BUCKET` | `s3.bucket` | 业务 bucket 名 | 是 | systemd / Docker env |
| 4 | `S3_ACCESS_KEY` | `s3.access-key` | S3 Access Key | 是 | Secret（systemd EnvironmentFile / Docker secret / K8s Secret） |
| 5 | `S3_SECRET_KEY` | `s3.secret-key` | S3 Secret Key | 是 | Secret |
| 6 | `GATEWAY_ENDPOINT` | `gateway.endpoint` | B2B Gateway 基址 | 是 | systemd / Docker env |
| 7 | `GATEWAY_APP_ID` | `gateway.app-id` | Gateway 应用 ID | 是 | Secret |
| 8 | `GATEWAY_APP_KEY` | `gateway.app-key` | Gateway 应用密钥 | 是 | Secret |
| 9 | `DB_URL` | `spring.datasource.url` | PostgreSQL JDBC URL（含 `sslmode=require` 及以上） | 是 | systemd / Docker env |
| 10 | `DB_APP_USERNAME` | `spring.datasource.username` | 应用数据库账号（最小权限，非迁移账号） | 是 | Secret |
| 11 | `DB_APP_PASSWORD` | `spring.datasource.password` | 应用数据库密码 | 是 | Secret |
| 12 | `DB_MIGRATION_USERNAME` | Flyway 连接 | 迁移专用账号（与应用分离，最小权限） | 是（仅迁移期） | Secret |
| 13 | `DB_MIGRATION_PASSWORD` | Flyway 连接 | 迁移账号密码 | 是（仅迁移期） | Secret |

### 4.3 部署注入方式

| 部署形态 | 注入方式 | 说明 |
|---|---|---|
| V1：systemd | `Environment=` / `EnvironmentFile=/etc/nas-s3-gateway/env`（权限 `600`，属主为运行用户） | 进程环境可见；文件不入 git |
| V1：Docker | `docker run --env-file` 或 Compose `environment:`；密钥优先用 Docker secret | 不要把密钥写进镜像或 `application.yml` |
| 未来：K8s | `Secret` → `envFrom` / `secretKeyRef` | 整体设计 §56 已预留，V1 不引入 K8s |

**校验**：若任一必填环境变量缺失，启动期校验必须失败并拒绝启动（见 §6 R13）。

---

## 5. 配置快照（config_version）

### 5.1 快照字段

任务创建时（基线 §2 迁移 T1/T2 阶段），把**执行所需**的配置固化进 `transfer_task`（整体设计 §6）：

| 快照字段 | 来源 | 为什么必须固化 |
|---|---|---|
| `directory_id` | `nas.directories[].id` | 目录配置可能被删除/禁用，任务仍需可解释 |
| `customer_space` | `nas.directories[].customer-space` | 防止"任务突然改变目标客户"（整体设计 §6） |
| `s3_bucket` | `s3.bucket` | bucket 变更不得改变已创建任务的写入目标 |
| `s3_object_key` | 由 `customer_space` + `relative_path` 推导（基线 §5） | 固化后即便拼接规则调整，旧任务仍按原 key 覆盖 |
| `gateway_route` | 由 `directory.customer_space` 映射 | 路由变更不影响在途/待执行任务 |
| `config_version` | 启动时计算的配置版本号（如 yml 文件 hash 或人工递增号） | 审计与排查"哪个配置版本执行了这个任务" |
| `directory_relative_path` | `nas.directories[].relative-path` | A6 未定，快照同时保存拼接结果，避免执行期再拼接 |

**明确不进入快照**：任何密钥（`S3_SECRET_KEY` / `GATEWAY_APP_KEY` / `DB_APP_PASSWORD`）、
`scan-interval`、`max-concurrency`、`retry.*`、`lease.*`、`supersede.*`、`retention.*`、`logging.*`。

### 5.2 生效语义

```text
T1：配置 V1
   ↓
创建 transfer_task（写入 config_version = V1 的快照字段）
   ↓
T2：管理员修改 application.yml → 重启（配置变为 V2）
   ↓
旧任务（READY / UPLOADING / WAITING_RETRY / GATEWAY_DELIVERING 等非终态）
   → 仍按快照 V1 执行
新任务（重启后 Scanner 发现的新文件）
   → 使用 V2 配置
```

- **不变式**：任务一旦创建，其 `s3_bucket` / `s3_object_key` / `gateway_route` / `customer_space`
  在任务生命周期内**不可变**，任何配置变更都不能修改已创建任务的快照字段。
- **允许变更的运行时参数**（不进快照）：并发上限、重试退避、lease 时长、supersede 阈值、
  保留策略、日志级别——这些只影响"未来调度决策"，不改变任务的目标。
- **禁止**：用新配置"纠正"旧任务的快照字段（会破坏 At-least-once 的可解释性）。

### 5.3 config_version 取值

| 方案 | 说明 | 建议 |
|---|---|---|
| yml 文件内容 SHA-256 | 自动生成，无需人工维护 | 推荐（只做审计标识，不含密钥内容） |
| 人工递增版本号 | 如 `v1`、`v2` | 可选，便于沟通 |
| 启动时间戳 | 简单但无区分度 | 不推荐 |

`config_version` 仅用于审计与排障，**不用于**任何自动迁移或回滚。

---

## 6. 启动期配置校验（fail-fast）

启动顺序遵循基线 §7：**DB 连接 → Recovery → Retry/Dispatcher + Worker → Scanner（最后）**。
配置校验发生在 DB 连接成功之后、Recovery 之前；**任一规则失败 → 拒绝启动**。

### 6.1 校验规则清单

| # | 规则 | 失败信息示例 |
|---|---|---|
| R1 | `nas.directories` 非空，且每个 `id` 唯一 | `nas.directories 为空` / `directory id 重复: customer-a-order` |
| R2 | 每个目录的绝对路径存在且可读（`mount-path + relative-path`） | `directory[customer-a-order] 路径不可读: /mnt/nas01/customer-a/order` |
| R3 | `nas.mount_path` 存在且是目录、可读 | `nas[nas-01] mount-path 不存在: /mnt/nas01` |
| R4 | `extensions` 非空且每个元素以 `.` 开头 | `directory[...] extensions 必须带点且非空` |
| R5 | `scan-interval > 0` | `directory[...] scan-interval 必须 > 0` |
| R6 | `max-concurrency >= 1` | `directory[...] max-concurrency 必须 >= 1` |
| R7 | `worker.upload.max-concurrency >= 1`、`worker.gateway.max-concurrency >= 1`、`worker.claim-batch-size >= 1`、`worker.queue-capacity >= 1` | `worker.* 必须为正整数` |
| R8 | `retry.multiplier >= 1`、`retry.initial-delay > 0`、`retry.max-delay >= retry.initial-delay` | `retry.multiplier 必须 >= 1` |
| R9 | `lease.duration > gateway.read-timeout` | `lease.duration(5m) 必须大于 gateway.read-timeout(60s)` |
| R10 | `supersede.grace-period >= gateway.read-timeout + lease.duration + 5m` | `supersede.grace-period(15m) 必须 >= gateway.read-timeout + lease.duration + 5m（60s+5m+5m = 11m）` |
| R11 | 每个目录的 `customer-space` 在 `customer_space` 表中存在且 `enabled = true` | `directory[...] customer-space 不存在: customer-x` |
| R12 | `(nas_id, path)` 组合唯一（yml 内 + DB `UNIQUE(nas_id, path)`） | `directory 唯一约束冲突: (nas-01, customer-a/order)` |
| R13 | 所有必填环境变量存在（§4.2 表 1–13） | `缺少必填环境变量: S3_SECRET_KEY` |
| R14 | `retention.only-terminal-states = true`（禁止改为 `false`） | `retention.only-terminal-states 必须为 true` |
| R15 | `s3.bucket` / `s3.endpoint` / `gateway.endpoint` 非空且为合法 URL/名称 | `s3.endpoint 非法` |
| R16 | `lease.renew-interval < lease.duration` 且 `> 0` | `lease.renew-interval 必须小于 lease.duration` |

### 6.2 校验失败行为

1. **拒绝启动**：抛出启动异常，进程以非 0 退出码结束，**不进入 Scanner / Worker 阶段**。
2. **明确错误信息**：一次性汇总**全部**失败项（不是遇到第一个就退出），每条包含
   `配置键` + `当前值` + `期望约束`，便于运维一次修完。
3. **不自动降级**：禁止"使用默认值兜底"或"跳过非法目录"——静默降级会导致任务丢失，
   违反基线的"不允许丢失"语义。
4. **不写库**：校验失败时不产生任何 `transfer_task` / `transfer_event`。
5. **记录日志**：以 `ERROR` 级别输出结构化日志（含 `instance-id`、`config_version`），并触发 `failure-alert-threshold` 告警链路。
6. **运行期不重复校验**：R2/R3/R11 等依赖外部状态的规则**仅在启动期校验**；
   运行期目录消失由 Scanner 按基线 T5/T7 处理为 `CANCELLED`，不导致进程退出。

---

## 7. 配置变更生效方式

| 配置组 | V1 生效方式 | 依据 |
|---|---|---|
| `nas.directories[]`（含 `enabled` / `scan-interval` / `max-concurrency` / `extensions`） | 修改 yml → **重启** 生效 | 整体设计 §78 |
| `s3.*` / `gateway.*` | 修改 yml → 重启；密钥走环境变量变更 + 重启 | 整体设计 §5、§78 |
| `worker.*` / `scanner.*` | 重启生效 | 整体设计 §78 |
| `retry.*` / `lease.*` / `supersede.*` | 重启生效 | 整体设计 §78 |
| `retention.*` / `logging.*` / `management.*` | 重启生效 | 整体设计 §78 |
| 已创建任务 | **不随配置变更**，按 §5 快照执行 | 整体设计 §6、§78 |
| 新任务 | 使用重启后的新配置 | 整体设计 §78 |

- V1 **不做**热更新、不做配置中心、不做动态刷新（`@RefreshScope` 一律不使用）。
- 重启遵循基线 §7 优雅关闭：① 停止扫描 → ② 停止 claim → ③ 等待在途 worker → ④ 完成当前上传 → ⑤ 释放 lease → ⑥ 退出。
- 目录**删除**（从 yml 移除）等价于 `enabled: false`：不删库中 `directory` 行，避免 FK 断裂。

---

## 8. 配置项总表

| 配置项 | 默认值 | 取值范围 | 是否敏感 | 是否进任务快照 | 影响模块 | 变更生效方式 |
|---|---|---|---|---|---|---|
| `app.instance-id` | `nas-s3-gateway-01` | 非空字符串 | 否 | 否 | config/monitoring | 重启 |
| `app.worker-id-prefix` | `worker` | 非空字符串 | 否 | 否 | worker | 重启 |
| `app.time-zone` | `Asia/Shanghai` | 合法时区 ID | 否 | 否 | config/scanner | 重启 |
| `spring.datasource.url` | 无（必填） | 合法 JDBC URL | 含主机信息，按敏感处理 | 否 | repository | 重启 |
| `spring.datasource.username` | 无（必填） | 非空 | 否 | 否 | repository | 重启 |
| `spring.datasource.password` | 无（必填） | 非空 | **是** | 否 | repository | 重启 |
| `spring.datasource.hikari.maximum-pool-size` | 20 | 1–100 | 否 | 否 | repository | 重启 |
| `spring.datasource.hikari.minimum-idle` | 5 | 0–maximum-pool-size | 否 | 否 | repository | 重启 |
| `spring.datasource.hikari.connection-timeout` | 10s | 1s–60s | 否 | 否 | repository | 重启 |
| `spring.datasource.hikari.validation-timeout` | 5s | 1s–30s | 否 | 否 | repository | 重启 |
| `spring.datasource.hikari.idle-timeout` | 10m | 1m–60m | 否 | 否 | repository | 重启 |
| `spring.datasource.hikari.max-lifetime` | 30m | > idle-timeout | 否 | 否 | repository | 重启 |
| `spring.datasource.hikari.leak-detection-threshold` | 60s | 0（关闭）或 ≥ 2s | 否 | 否 | repository | 重启 |
| `spring.task.scheduling.pool.size` | 4 | 1–8 | 否 | 否 | worker/scanner | 重启 |
| `nas.directories[].id` | 无（必填） | 唯一、非空 | 否 | 是（日志/审计） | config/scanner | 重启 |
| `nas.directories[].nas-id` | 无（必填） | 对应 `nas` 表 | 否 | 是 | scanner | 重启 |
| `nas.directories[].mount-path` | 无（必填） | 绝对路径、存在可读 | 否 | 是 | scanner | 重启 |
| `nas.directories[].relative-path` / `path` | 无（必填） | 相对路径（见 A6） | 否 | 是 | scanner/storage | 重启 |
| `nas.directories[].customer-space` | 无（必填） | 存在于 `customer_space` | 否 | 是 | storage/gateway | 重启 |
| `nas.directories[].extensions[]` | 无（必填） | 非空、带点 | 否 | 是 | scanner | 重启 |
| `nas.directories[].scan-interval` | `6h` | > 0 | 否 | 否 | scanner | 重启 |
| `nas.directories[].max-concurrency` | 4 | 1–8（建议 2–4） | 否 | 否 | worker | 重启 |
| `nas.directories[].enabled` | `true` | true/false | 否 | 否 | scanner | 重启 |
| `nas.directories[].stability-recheck-interval` | 5m | > 0 | 否 | 否 | scanner | 重启 |
| `s3.endpoint` | 无（必填） | 合法 URL | 否 | 否 | storage | 重启 |
| `s3.region` | 无（必填） | 非空 | 否 | 否 | storage | 重启 |
| `s3.bucket` | 无（必填） | 非空 | 否 | **是** | storage | 重启（新任务） |
| `s3.access-key` | 无（必填） | 非空 | **是** | **否** | storage | 重启 |
| `s3.secret-key` | 无（必填） | 非空 | **是** | **否** | storage | 重启 |
| `s3.path-style-access` | `true` | true/false | 否 | 否 | storage | 重启 |
| `s3.connect-timeout` | 10s | 1s–60s | 否 | 否 | storage | 重启 |
| `s3.socket-timeout` | 60s | 1s–10m | 否 | 否 | storage | 重启 |
| `s3.max-retries` | 3 | 0–10 | 否 | 否 | storage | 重启 |
| `gateway.endpoint` | 无（必填） | 合法 URL | 否 | 否 | gateway | 重启 |
| `gateway.app-id` | 无（必填） | 非空 | **是** | **否** | gateway | 重启 |
| `gateway.app-key` | 无（必填） | 非空 | **是** | **否** | gateway | 重启 |
| `gateway.connect-timeout` | 10s | 1s–60s | 否 | 否 | gateway | 重启 |
| `gateway.read-timeout` | 60s | 1s–10m | 否 | 否 | gateway/lease | 重启 |
| `gateway.max-inflight` | 8 | 1–64 | 否 | 否 | gateway | 重启 |
| `worker.upload.max-concurrency` | 8 | 1–64 | 否 | 否 | worker | 重启 |
| `worker.gateway.max-concurrency` | 8 | 1–64 | 否 | 否 | worker | 重启 |
| `worker.claim-batch-size` | 16 | 1–256 | 否 | 否 | worker | 重启 |
| `worker.queue-capacity` | 16 | 1–64（必须有界） | 否 | 否 | worker | 重启 |
| `worker.use-virtual-threads` | `true` | true/false（须配 Semaphore） | 否 | 否 | worker | 重启 |
| `retry.initial-delay` | 30s | > 0 | 否 | 否 | retry | 重启 |
| `retry.multiplier` | 2 | ≥ 1 | 否 | 否 | retry | 重启 |
| `retry.max-delay` | 1h | ≥ initial-delay | 否 | 否 | retry | 重启 |
| `retry.jitter` | 20% | 0%–50% | 否 | 否 | retry | 重启 |
| `retry.max-retry-count` | 20 | ≥ 1（**仅阈值，非永久停止**） | 否 | 否 | retry/supersede | 重启 |
| `lease.duration` | 5m | > gateway.read-timeout | 否 | 否 | worker/recovery | 重启 |
| `lease.renew-interval` | 1m40s | 0 < x < lease.duration | 否 | 否 | worker | 重启 |
| `lease.stale-scan-interval` | 1m | > 0 | 否 | 否 | recovery | 重启 |
| `supersede.enabled` | `true` | true/false | 否 | 否 | task/supersede | 重启 |
| `supersede.grace-period` | 15m | ≥ read-timeout + lease.duration + 5m | 否 | 否 | supersede | 重启 |
| `supersede.after-failed-attempts` | 5 | ≥ 1 | 否 | 否 | supersede | 重启 |
| `supersede.after-waiting` | 30m | > 0 | 否 | 否 | supersede | 重启 |
| `scanner.thread-pool-size` | 4 | 1–16 | 否 | 否 | scanner | 重启 |
| `scanner.max-files-per-scan` | 10000 | 1–100000 | 否 | 否 | scanner | 重启 |
| `scanner.follow-symlinks` | `false` | true/false | 否 | 否 | scanner | 重启 |
| `scanner.failure-alert-threshold` | 3 | ≥ 1 | 否 | 否 | monitoring | 重启 |
| `retention.enabled` | `true` | true/false | 否 | 否 | maintenance | 重启 |
| `retention.keep-days` | 365 | ≥ 1（业务为 1 年） | 否 | 否 | maintenance | 重启 |
| `retention.cleanup-cron` | `0 30 3 * * *` | 合法 cron | 否 | 否 | maintenance | 重启 |
| `retention.only-terminal-states` | `true` | **必须 true** | 否 | 否 | maintenance | 重启 |
| `logging.level` | `INFO` | TRACE–ERROR | 否 | 否 | monitoring | 重启 |
| `logging.format` | `json` | json/plain | 否 | 否 | monitoring | 重启 |
| `logging.mask-sensitive` | `true` | true/false（生产须 true） | 否 | 否 | monitoring | 重启 |
| `management.endpoints.web.exposure.include` | `health,metrics,prometheus` | 逗号分隔 | 否 | 否 | monitoring | 重启 |
| `management.metrics.tags.*` | 见 §2 | 非空 | 否 | 否 | monitoring | 重启 |

---

## 9. 【架构问题】标注

### 【架构问题】A6：`directory.path` 与 `relative path` 的关系未定义

**问题**
基线 §10 的 A6 指出：`directory.path` 与 `relative path` 的关系未定义。展开为三点：

1. **拼接规则未定义**。整体设计 §31 的 `directory` 表同时存在 `path`（如 `/mnt/nas01/customer-a/order`）
   与 `nas.mount_path`（如 `/mnt/nas01`），二者关系未说明：`path` 是"含挂载点的绝对路径"还是
   "相对挂载点的相对路径"？本文 §2 的 yml 因此同时列出 `mount-path` + `relative-path`（或 `path`）两种写法。
2. **`file.relative_path` 的基准未定义**。整体设计 §32 的 `UNIQUE(nas_id, directory_id, relative_path)`
   说明 `relative_path` 至少在 directory 维度唯一，但**相对谁**（directory 根？NAS 根？）未说明。
3. **S3 key 推导规则与 §40 示例自相矛盾**。
   - 基线 §5：`<customer-space-code>/<directory-relative-path>/<file-relative-path>`
   - 整体设计 §40：`<customer-space>/<relative-path>`，示例 `NAS /mnt/nas01/customer-a/order/A.zip`
     → `S3 customer-a/order/A.zip`
   - 若按 §40 字面拼接，`<customer-space>` = `customer-a` 且 `<relative-path>` 已含 `customer-a/order/A.zip`，
     结果为 `customer-a/customer-a/order/A.zip`，与示例不符。

**风险**
- 不同实现者可能得到不同 S3 Object Key → 同一文件被写入两个 key，或覆盖错误对象。
- 快照字段 `s3_object_key`（整体设计 §6）的取值不可复现，破坏"旧任务按快照执行"的可解释性。
- Gateway 按 key 读取对象时可能读到旧版本，放大基线 §3.5 的覆盖竞态。

**建议方案（仅建议，未改动架构）**
1. 明确约定：`directory.path` = **相对 `nas.mount_path` 的相对路径**（如 `customer-a/order`），
   绝对路径 = `nas.mount_path + directory.path`；`nas` 表保留 `mount_path` 作为唯一挂载点来源。
2. 明确约定：`file.relative_path` = **相对 directory 根**的相对路径（如 `2026/09/A.zip`）。
3. 明确 S3 key 推导：`<customer_space.code>/<directory.path>/<file.relative_path>`，
   并声明 `directory.path` **不得**以 `customer_space.code` 开头（否则出现重复前缀）。
4. 在 `transfer_task` 快照中保存**推导结果** `s3_object_key`（而非仅保存输入），
   保证规则调整后旧任务仍按原 key 执行。
5. 上述 1–4 需由权威基线确认后再落实现；本文仅按此"建议"书写示例，未修改任何已确认设计。

**对现有设计的影响**
- 若采纳建议 1，`directory.path` 的语义收窄为相对路径，`UNIQUE(nas_id, path)` 语义不变，
  DDL 无需变更（`VARCHAR(1024)` 仍足够）。
- 若维持"`path` 为绝对路径"，则 yml 中 `mount-path` 与 `path` 冗余，需在启动校验中
  强制 `path` 以 `mount_path` 开头（可作为 R2 的加强项）。
- 两种方案对状态机、迁移矩阵、并发模型、崩溃恢复**均无影响**。

### 【架构问题】（新发现，建议编号 A9，编号待权威基线确认）：单个 `s3.bucket` 与"一个 NAS → 一个固定 bucket"不一致

**问题**：基线 §0 明确规模为 **2 个 NAS**；整体设计 §40 写明"一个 NAS → 一个固定 bucket"。
但本文 yml 中 `s3.bucket` 是**全局单值**，若两个 NAS 需写入不同 bucket，则无法表达；
若两 NAS 共用一个 bucket，则与 §40 的"一个 NAS → 一个固定 bucket"表述不一致。

**风险**：多 NAS 场景下若共用 bucket 且 S3 key 不含 NAS 前缀，不同 NAS 上的同名相对路径会互相覆盖，
且无法通过快照 `s3_bucket` 区分。

**建议方案（未改动架构）**：
- 方案 A（推荐，改动最小）：保留全局 `s3.bucket`，并在 `nas.directories[]` 增加可选 `s3-bucket` 覆盖项，
  未配置时回退到全局值；快照 `s3_bucket` 记录实际生效值。
- 方案 B：S3 key 增加 NAS 前缀 `<nas-id>/<customer-space>/<relative-path>`——**与基线 §5 冲突，不建议**。
- 方案 C：明确 V1 两个 NAS 共用一个 bucket，并接受"同名相对路径覆盖"为已知语义——需业务确认。

**对现有设计的影响**：仅影响 `s3.bucket` 的配置粒度与快照取值来源；不影响状态机、并发与恢复语义。
在确认前，本文按"全局单值 + 快照记录"书写。

### 其他已知开放问题（非架构问题，来自基线 §10）

| 编号 | 与配置相关的开放问题 | 本文的临时处理 |
|---|---|---|
| Q1 | 扫描周期 24h 的目录，稳定确认是否需等一整个周期？是否引入 `stabilityRecheckInterval` | yml 保留 `stability-recheck-interval`（默认 30s），待确认 |
| Q2 | mtime 精度与 fingerprint 取值（秒/纳秒/时区） | 配置 `app.time-zone` 统一时区；精度待确认 |
| Q3 | Gateway 认证方式、成功响应契约、幂等键 | 配置仅保留 `app-id`/`app-key`，协议待确认 |
| Q6 | 是否需要上传后校验（size/ETag 比对） | 未引入配置项，待确认 |

---

## 10. 与其他交付文档的关系

| 文档 | 关系 |
|---|---|
| `01` 需求与范围 | 本文的配置项服务于其范围定义 |
| 数据模型文档 | `directory` / `nas` / `customer_space` 表结构是 yml 配置的持久化映射 |
| 状态机文档 | 配置影响迁移守卫（并发配额、supersede 阈值、lease） |
| 并发模型文档 | `worker.*` 与 `directory.max-concurrency` 的取值在此定义 |
| 崩溃恢复文档 | `lease.*` 与启动顺序依赖本文 §6 校验 |
| 数据保留文档 | `retention.*` 的语义与 `only-terminal-states: true` 在此定义 |
| `13-open-questions-risks.md` | A6 与建议 A9 需在该文档登记并跟踪 |

---

**文档结束。本文仅为配置模型说明，不含任何可运行代码或真实密钥。**
