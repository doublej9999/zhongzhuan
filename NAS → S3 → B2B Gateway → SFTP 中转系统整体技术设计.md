# NAS → S3 → B2B Gateway → SFTP 中转系统
## 整体技术设计

**版本：** V1.0  
**技术栈：** JDK 21 + PostgreSQL  
**部署：** 单机  
**任务可靠性：** At-least-once  
**核心原则：** 不丢文件、可恢复、可追踪、最终一致

---

# 1. 背景

系统负责将 NAS 上指定目录中的文件自动中转到客户 SFTP。

整体链路：

```text
                  ┌─────────────────┐
                  │    NAS / SMB    │
                  │                 │
                  │ /customer-a/... │
                  │ /customer-b/... │
                  └────────┬────────┘
                           │
                     SMB/CIFS Read
                           │
                           ▼
                  ┌─────────────────┐
                  │ Transfer App    │
                  │                 │
                  │ Scanner         │
                  │ Scheduler       │
                  │ Worker          │
                  │ State Machine   │
                  │ Retry           │
                  └────────┬────────┘
                           │
                    S3 Object Upload
                           │
                           ▼
                  ┌─────────────────┐
                  │   Platform S3   │
                  │                 │
                  │ Fixed Bucket    │
                  └────────┬────────┘
                           │
                     Gateway API
                           │
                           ▼
                  ┌─────────────────┐
                  │  B2B Gateway    │
                  │                 │
                  │ Customer Space  │
                  │       ↓         │
                  │      SFTP       │
                  └─────────────────┘
```

---

# 2. 已确认的业务约束

## 2.1 NAS

- NAS 使用 SMB/CIFS。
- 应用运行机器已经 mount NAS。
- 应用第一阶段只读 NAS。
- 当前：
  - 2 个 NAS
  - 约 20 个目录
  - 每天约 1000 个文件
  - 每天约 5GB
  - 单文件 1KB ~ 10MB
  - 单目录最多约 1 万文件
- NAS 原文件暂不删除、不移动。
- 未来可能增加 archive/delete 能力。

---

## 2.2 目录与客户

一个目录只对应一个客户空间：

```text
directory → customer space
```

一个客户可以对应多个目录：

```text
customer A
 ├── directory A
 ├── directory B
 └── directory C
```

不允许：

```text
directory A
 ├── customer A
 └── customer B
```

---

## 2.3 文件发现

每个目录独立配置：

- 扫描周期
- 文件后缀
- 客户空间
- 并发限制

扫描周期可以从几分钟到数小时甚至一天。

扫描到时间后：

> 尽快处理该目录中所有待处理文件。

文件只有满足以下条件才进入上传：

```text
连续两次扫描：
path 相同
size 相同
mtime 相同
```

即认为文件稳定。

业务约束：

> 文件一旦进入稳定状态，上游不得继续修改。

---

## 2.4 文件变化

文件逻辑身份：

```text
NAS + Directory + Relative Path
```

文件版本通过：

```text
size + mtime
```

识别。

例如：

```text
A.zip
v1:
  size = 10MB
  mtime = T1

v2:
  size = 12MB
  mtime = T2
```

认为是两个文件版本。

数据库保存版本历史一年。

S3 不保存版本。

S3 Object Key 固定：

```text
customer-space/A.zip
```

新版本直接覆盖旧版本。

---

# 3. 核心架构

V1 不使用 Kafka、RabbitMQ 等 MQ。

PostgreSQL 同时承担：

1. Source of Truth
2. Task Durable Store
3. State Store
4. Retry Store
5. Audit Store

应用内部使用：

> PostgreSQL + 有界线程池 / Executor

整体：

```text
                  ┌────────────────────┐
                  │    Configuration    │
                  │ application.yml     │
                  └─────────┬──────────┘
                            │
                            ▼
┌───────────┐       ┌────────────────────┐
│ Scheduler │──────▶│      Scanner       │
└───────────┘       └─────────┬──────────┘
                              │
                              ▼
                     ┌─────────────────┐
                     │   PostgreSQL    │
                     │                 │
                     │ file            │
                     │ file_version    │
                     │ transfer_task   │
                     │ transfer_attempt│
                     └────────┬────────┘
                              │
                       Claim / Lease
                              │
                              ▼
                     ┌─────────────────┐
                     │ Worker Executor │
                     └───────┬─────────┘
                             │
                  ┌──────────┴──────────┐
                  │                     │
                  ▼                     ▼
             SMB / NAS                  S3
                                        │
                                        ▼
                                  B2B Gateway
                                        │
                                        ▼
                                      SFTP
```

---

# 4. 模块划分

建议应用内部拆成以下模块。

```text
com.xxx.transfer
│
├── config
│
├── scanner
│   ├── DirectoryScanner
│   ├── FileStabilityChecker
│   └── ScanScheduler
│
├── file
│   ├── FileFingerprint
│   └── FileMetadata
│
├── task
│   ├── TaskService
│   ├── TaskClaimService
│   ├── TaskRecoveryService
│   └── TaskStateMachine
│
├── worker
│   ├── UploadWorker
│   ├── GatewayWorker
│   └── WorkerExecutor
│
├── storage
│   ├── S3Uploader
│   └── SmbFileReader
│
├── gateway
│   └── B2BGatewayClient
│
├── retry
│   └── RetryScheduler
│
├── repository
│
├── audit
│
└── monitoring
```

模块职责明确隔离。

---

# 5. 配置设计

第一阶段不做前端。

业务配置放：

```text
application.yml
```

敏感配置放：

```text
Environment Variables
```

例如：

```yaml
nas:
  directories:

    - id: customer-a-order
      path: /mnt/nas01/customer-a/order
      customerSpace: customer-a
      extensions:
        - ".zip"
        - ".xml"
      scanInterval: 6h
      maxConcurrency: 4

    - id: customer-a-invoice
      path: /mnt/nas01/customer-a/invoice
      customerSpace: customer-a
      extensions:
        - ".csv"
      scanInterval: 24h
      maxConcurrency: 2
```

S3：

```yaml
s3:
  endpoint: ${S3_ENDPOINT}
  bucket: ${S3_BUCKET}
  accessKey: ${S3_ACCESS_KEY}
  secretKey: ${S3_SECRET_KEY}
```

Gateway：

```yaml
gateway:
  endpoint: ${GATEWAY_ENDPOINT}
  appId: ${GATEWAY_APP_ID}
  appKey: ${GATEWAY_APP_KEY}
```

---

# 6. 配置快照

任务创建时，把任务运行所需要的配置固化到任务中。

例如：

```text
transfer_task
 ├── directory_id
 ├── customer_space
 ├── s3_bucket
 ├── s3_object_key
 ├── gateway_route
 └── config_version
```

这样：

```text
T1
配置 V1
 ↓
创建任务
 ↓
T2
管理员修改配置为 V2
```

T1 创建的任务仍然按照 V1 执行。

不会出现：

```text
READY task
 ↓
配置改变
 ↓
任务突然改变目标客户
```

---

# 7. 文件模型

建议把：

> 逻辑文件

和：

> 文件版本

分开。

## file

代表 NAS 上的逻辑路径。

例如：

```text
NAS01/customer-a/A.zip
```

字段：

```text
id
nas_id
directory_id
relative_path
created_at
updated_at
```

唯一约束：

```text
(nas_id, directory_id, relative_path)
```

---

## file_version

代表某次观察到的文件版本。

字段：

```text
id
file_id
size
mtime
fingerprint
first_seen_at
stable_at
status
created_at
updated_at
```

fingerprint：

```text
SHA-256(
    relative_path
    + size
    + mtime
)
```

注意：

> fingerprint 是检测身份的辅助值，不是文件内容 hash。

因此不会为了发现变化而读取整个文件。

---

# 8. 为什么不计算文件 SHA-256

当前单文件最大只有 10MB，但文件数量长期累计可能较多。

如果每次扫描都：

```text
读取完整文件
 ↓
SHA-256
```

会产生不必要的：

- SMB IO
- NAS 磁盘 IO
- 网络流量

业务已经明确使用：

```text
path + size + mtime
```

所以 V1 不计算内容 hash。

未来如果出现特殊场景，可以配置：

```text
fingerprint.strategy = SIZE_MTIME
```

扩展为：

```text
fingerprint.strategy = SHA256
```

---

# 9. 文件稳定性检测

扫描第一次发现：

```text
A.zip
size = 10MB
mtime = T1
```

创建：

```text
file_version
status = STABILITY_CHECK
```

下一次扫描：

```text
A.zip
size = 10MB
mtime = T1
```

一致。

于是：

```text
STABILITY_CHECK
       ↓
READY
```

如果变化：

```text
A.zip
size = 11MB
mtime = T2
```

更新观察值：

```text
STABILITY_CHECK
```

重新开始稳定性判断。

---

# 10. Scanner 设计

每个目录拥有独立调度任务。

例如：

```text
Directory A → every 1h
Directory B → every 6h
Directory C → every 24h
```

不要创建一个全局：

```text
scanAllDirectories()
```

而是：

```text
ScheduledDirectoryScan
```

每个 directory 独立调度。

---

# 11. 扫描过程

一个目录扫描：

```text
1. 获取目录配置
2. SMB list
3. 根据后缀过滤
4. 获取：
   - relative path
   - size
   - mtime
5. 查询 DB
6. 判断：
   - 新文件
   - 稳定文件
   - 新版本
   - 已处理版本
7. 创建/更新 file_version
8. 创建 transfer_task
9. 将可执行任务交给 worker
```

由于单目录最多 1 万文件，当前阶段：

> 全目录扫描 + 数据库增量判断

完全可以接受。

---

# 12. 文件不存在处理

扫描时 DB 中存在：

```text
A.zip
```

但 SMB 当前不存在。

如果任务尚未开始读取：

```text
STABILITY_CHECK
READY
```

则：

```text
→ CANCELLED
```

如果已经：

```text
UPLOADING
```

则：

> 不因为 NAS 删除而中断已经开始的读取。

原因：

SMB/操作系统已经打开文件句柄后，文件是否从目录中消失和当前读取过程是两个不同问题。

---

# 13. 状态机

核心状态保持简单。

```text
DISCOVERED
     │
     ▼
STABILITY_CHECK
     │
     │ 两次一致
     ▼
READY
     │
     ▼
UPLOADING
     │
     ▼
S3_UPLOADED
     │
     ▼
GATEWAY_DELIVERING
     │
     ├──── failure ────▶ WAITING_RETRY
     │                         │
     │                         │ next_retry_at
     │                         ▼
     │                   GATEWAY_DELIVERING
     │
     ▼
DELIVERED
```

取消：

```text
STABILITY_CHECK → CANCELLED
READY           → CANCELLED
```

---

# 14. 为什么不把 worker claim 做成状态

不增加：

```text
CLAIMED
PROCESSING
...
```

这种大量状态。

而是：

```text
transfer_task
```

保存业务状态。

另外保存：

```text
worker_id
lease_until
claimed_at
```

例如：

```text
status = UPLOADING

worker_id = worker-01
lease_until = 2026-09-09 10:05:00
```

这样：

> 业务状态和运行时 ownership 分离。

---

# 15. Worker Claim

Worker 从数据库领取任务。

核心思想：

```sql
SELECT ...
FROM transfer_task
WHERE status = 'READY'
  AND next_retry_at <= now()
ORDER BY priority, created_at
FOR UPDATE SKIP LOCKED
LIMIT ?
```

然后：

```text
BEGIN
  SELECT ... FOR UPDATE SKIP LOCKED
  UPDATE task
     SET worker_id = ?,
         lease_until = ?
COMMIT
```

虽然当前只有单实例，仍然使用这种模式。

原因：

> 为未来水平扩展留下正确的数据模型，同时避免多个内部 worker 抢同一个任务。

---

# 16. Lease

例如：

```text
leaseDuration = 5 minutes
```

worker 开始任务：

```text
lease_until = now + 5min
```

worker 定期续租。

如果 JVM：

```text
kill -9
```

那么：

```text
lease_until < now()
```

任务就成为 stale task。

Recovery Scheduler 自动恢复。

---

# 17. Crash Recovery

例如：

```text
UPLOADING
worker-01
lease_until = 10:00
```

程序：

```text
09:58 crash
```

重新启动：

```text
10:01
```

发现：

```text
lease_until < now()
```

则：

```text
UPLOADING
    ↓
READY
```

重新执行。

由于：

> At-least-once

不尝试猜测之前到底成功还是失败。

直接重新执行。

---

# 18. 为什么允许重复执行

例如：

```text
S3 PUT
 ↓
实际已经成功
 ↓
JVM crash
```

数据库仍然：

```text
UPLOADING
```

重启以后：

```text
重新 PUT
```

S3：

```text
customer/A.zip
```

被覆盖。

这是允许的。

最终：

```text
S3 = 正确文件
```

不会产生多版本。

---

# 19. 同一路径版本串行

这是本系统非常重要的并发规则。

不同文件：

```text
A.zip ──────────────┐
B.zip ──────────────┤
C.zip ──────────────┤ → 并行
D.zip ──────────────┘
```

同一个 path：

```text
A.zip v1
   ↓
S3
   ↓
Gateway
   ↓
DELIVERED
   ↓
A.zip v2
```

默认串行。

这样可以避免：

```text
v1 Gateway
       ↓
从 S3 获取 A.zip
       ↑
v2 已经覆盖 A.zip
```

导致 Gateway 实际拿到错误版本。

---

# 20. 同一路径串行的数据库约束

逻辑上：

```text
file_id
```

作为串行化粒度。

例如：

```text
file_id = 100
```

当前：

```text
v1 = GATEWAY_DELIVERING
```

那么：

```text
v2
```

即使已经发现并稳定，也不能进入实际上传。

它可以保持：

```text
READY
```

但 worker claim 时必须检查：

> 同一个 file_id 是否存在未完成的前置版本。

这样：

```text
v1 → DELIVERED
```

之后：

```text
v2 → READY → UPLOADING
```

---

# 21. 版本最终一致性

由于 S3 Object Key 不做版本：

```text
customer/A.zip
```

最终：

```text
S3 = 最新成功上传版本
```

例如：

```text
v1 → Gateway 成功
v2 → Gateway 失败
v3 → Gateway 成功
```

最终：

```text
S3 = v3
SFTP = v3
```

客户可能看不到 v2。

这是允许的。

业务要求：

> 当前文件最终可靠中转。

而不是：

> 每一个历史版本必须投递。

---

# 22. Gateway API

假设接口：

```http
POST /delivery
```

请求：

```json
{
  "bucket": "bucket-a",
  "objectKey": "customer-a/A.zip",
  "customerSpace": "customer-a"
}
```

认证：

```text
AppId
AppKey
```

Gateway：

```text
1. 接收请求
2. 从 S3 获取 object
3. 连接 customer SFTP
4. 上传文件
5. 成功后返回
```

本设计假设：

> API 返回成功 = 文件已经落盘到客户 SFTP。

---

# 23. Gateway 调用时序

```text
Transfer App
     │
     │ PUT S3
     ▼
    S3
     │
     │ success
     ▼
Transfer App
     │
     │ POST Gateway
     ▼
B2B Gateway
     │
     │ GET S3
     ▼
    S3
     │
     ▼
Gateway
     │
     │ SFTP upload
     ▼
Customer SFTP
     │
     │ success
     ▼
Gateway
     │
     │ 200
     ▼
Transfer App
```

---

# 24. Gateway 超时

例如：

```text
App
 ↓
Gateway
 ↓
SFTP success
 ↓
Gateway response lost
 ↓
App timeout
```

应用无法判断 Gateway 是否成功。

由于系统选择：

> At-least-once + 允许重复投递

因此：

```text
timeout
 ↓
WAITING_RETRY
 ↓
再次调用 Gateway
```

即使客户收到两次：

```text
A.zip
A.zip
```

也接受。

---

# 25. Retry

Retry 不占用 worker。

错误后：

```text
status = WAITING_RETRY
next_retry_at = ...
retry_count = N
```

Worker 释放。

到时间后 Scheduler/Dispatcher 再次领取。

---

# 26. Exponential Backoff

配置：

```yaml
retry:
  initialDelay: 30s
  multiplier: 2
  maxDelay: 1h
  jitter: 20%
```

例如：

```text
30s
1m
2m
4m
8m
16m
32m
1h
1h
1h
...
```

retry 次数：

> 可配置。

但是：

> 达到 retry 次数并不删除任务。

因为业务要求：

> 永远 retry，直到成功。

因此：

```text
retry_count = 100
```

仍然可以：

```text
WAITING_RETRY
```

---

# 27. Retry History

不把所有 retry 信息都塞到 task 表。

核心模型：

```text
transfer_task
       │
       │ 1:N
       ▼
transfer_attempt
```

例如：

```text
Task #100
 ├── Attempt #1
 │    Gateway timeout
 │
 ├── Attempt #2
 │    HTTP 500
 │
 ├── Attempt #3
 │    SFTP connection refused
 │
 └── Attempt #4
      success
```

---

# 28. transfer_task

建议核心字段：

```text
id
file_version_id

directory_id
customer_space_id

file_path
file_size
file_mtime

s3_bucket
s3_object_key

config_version

status

retry_count
next_retry_at

worker_id
lease_until
claimed_at

created_at
updated_at
started_at
completed_at
```

任务中保存关键 snapshot。

---

# 29. transfer_attempt

建议：

```text
id
transfer_task_id

attempt_type
attempt_no

started_at
finished_at

success

http_status

error_code
error_message

request_id
gateway_request_id

duration_ms

created_at
```

`attempt_type`：

```text
S3_UPLOAD
GATEWAY_DELIVERY
```

这样运维可以完整追踪。

---

# 30. 数据库核心关系

```text
nas
 │
 └── directory
       │
       └── file
             │
             └── file_version
                    │
                    └── transfer_task
                           │
                           └── transfer_attempt
```

客户：

```text
customer_space
       │
       └── directory
```

因此：

```text
customer_space 1 ─── N directory
directory       1 ─── N file
file            1 ─── N file_version
file_version    1 ─── 1 transfer_task
transfer_task   1 ─── N transfer_attempt
```

---

# 31. 推荐 PostgreSQL 表

## nas

```sql
CREATE TABLE nas (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    mount_path      VARCHAR(1024) NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);
```

---

## customer_space

```sql
CREATE TABLE customer_space (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(128) NOT NULL UNIQUE,
    name            VARCHAR(255) NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);
```

---

## directory

```sql
CREATE TABLE directory (
    id                  BIGSERIAL PRIMARY KEY,
    nas_id              BIGINT NOT NULL REFERENCES nas(id),
    customer_space_id   BIGINT NOT NULL REFERENCES customer_space(id),

    path                VARCHAR(1024) NOT NULL,

    scan_interval_sec   BIGINT NOT NULL,
    max_concurrency     INTEGER NOT NULL,

    extensions          JSONB NOT NULL,

    enabled             BOOLEAN NOT NULL DEFAULT TRUE,

    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,

    UNIQUE(nas_id, path)
);
```

---

# 32. file

```sql
CREATE TABLE file (
    id                  BIGSERIAL PRIMARY KEY,

    nas_id              BIGINT NOT NULL,
    directory_id        BIGINT NOT NULL REFERENCES directory(id),

    relative_path       VARCHAR(2048) NOT NULL,

    created_at           TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL,

    UNIQUE(nas_id, directory_id, relative_path)
);
```

---

# 33. file_version

```sql
CREATE TABLE file_version (
    id                  BIGSERIAL PRIMARY KEY,

    file_id             BIGINT NOT NULL REFERENCES file(id),

    size_bytes          BIGINT NOT NULL,
    mtime                TIMESTAMPTZ NOT NULL,

    fingerprint          VARCHAR(128) NOT NULL,

    first_seen_at        TIMESTAMPTZ NOT NULL,
    stable_at            TIMESTAMPTZ,

    status               VARCHAR(32) NOT NULL,

    created_at           TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL,

    UNIQUE(file_id, fingerprint)
);
```

---

# 34. transfer_task

```sql
CREATE TABLE transfer_task (
    id                  BIGSERIAL PRIMARY KEY,

    file_version_id     BIGINT NOT NULL
                        REFERENCES file_version(id),

    directory_id        BIGINT NOT NULL,
    customer_space_id   BIGINT NOT NULL,

    file_path            VARCHAR(2048) NOT NULL,
    file_size_bytes      BIGINT NOT NULL,
    file_mtime           TIMESTAMPTZ NOT NULL,

    s3_bucket             VARCHAR(255) NOT NULL,
    s3_object_key         VARCHAR(2048) NOT NULL,

    config_version        VARCHAR(64) NOT NULL,

    status                VARCHAR(32) NOT NULL,

    retry_count           INTEGER NOT NULL DEFAULT 0,
    next_retry_at         TIMESTAMPTZ,

    worker_id             VARCHAR(128),
    lease_until           TIMESTAMPTZ,
    claimed_at            TIMESTAMPTZ,

    started_at            TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ,

    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,

    UNIQUE(file_version_id)
);
```

---

# 35. transfer_attempt

```sql
CREATE TABLE transfer_attempt (
    id                  BIGSERIAL PRIMARY KEY,

    transfer_task_id    BIGINT NOT NULL
                        REFERENCES transfer_task(id),

    attempt_type        VARCHAR(32) NOT NULL,
    attempt_no          INTEGER NOT NULL,

    started_at          TIMESTAMPTZ NOT NULL,
    finished_at         TIMESTAMPTZ,

    success             BOOLEAN NOT NULL,

    http_status         INTEGER,

    error_code          VARCHAR(128),
    error_message       TEXT,

    request_id          VARCHAR(128),
    gateway_request_id  VARCHAR(128),

    duration_ms         BIGINT,

    created_at          TIMESTAMPTZ NOT NULL,

    UNIQUE(transfer_task_id, attempt_type, attempt_no)
);
```

---

# 36. 任务创建的事务边界

Scanner 不应该：

```text
先 Java 判断
再 INSERT
```

完全依赖 Java 判断。

而应该：

```text
BEGIN

发现文件

INSERT file
ON CONFLICT DO NOTHING

INSERT file_version
ON CONFLICT DO NOTHING

INSERT transfer_task
ON CONFLICT DO NOTHING

COMMIT
```

最终防线：

> PostgreSQL UNIQUE constraint。

---

# 37. 为什么数据库约束非常重要

例如两个 Scanner：

```text
Scanner A
Scanner B
```

同时发现：

```text
A.zip
size=100
mtime=T1
```

两边都认为：

> 这是新文件。

最终：

```text
INSERT file_version
```

只有一个成功。

另一个：

```text
ON CONFLICT DO NOTHING
```

因此不会出现：

```text
两个 task
```

---

# 38. 同一路径串行的实现

可以在 claim 时：

```sql
SELECT task
FROM transfer_task t
WHERE ...
  AND NOT EXISTS (
      SELECT 1
      FROM transfer_task previous
      WHERE previous.file_id = ?
        AND previous.id < t.id
        AND previous.status NOT IN ('DELIVERED', 'CANCELLED')
  )
FOR UPDATE SKIP LOCKED;
```

实际实现时建议根据版本顺序增加：

```text
file_id
version sequence
```

避免依赖 ID 的隐含语义。

---

# 39. 推荐增加 version_no

虽然 fingerprint 已经可以识别版本，但为了任务排序和审计，建议：

```text
file_version.version_no
```

例如：

```text
file_id = 100

version_no = 1
version_no = 2
version_no = 3
```

唯一：

```text
UNIQUE(file_id, version_no)
```

这样同一个文件的版本关系非常清晰。

---

# 40. S3 Object Key

由于：

```text
一个 NAS → 一个固定 bucket
```

建议 Object Key 使用：

```text
<customer-space>/<relative-path>
```

例如：

```text
customer-a/order/2026/09/A.zip
```

这样：

```text
NAS:
/mnt/nas01/customer-a/order/A.zip

S3:
bucket-nas01/
customer-a/order/A.zip
```

不要加入：

```text
UUID
timestamp
version
```

因为业务要求：

> 新版本覆盖旧版本。

---

# 41. S3 上传

由于：

```text
最大文件 = 10MB
```

V1：

> 默认普通 PutObject。

不做 Multipart。

架构接口仍然抽象成：

```java
interface ObjectStorageUploader {

    UploadResult upload(
        Path source,
        String bucket,
        String objectKey
    );
}
```

未来如果文件扩大：

```text
10MB
→ 1GB
```

只需要在实现层增加：

```text
MultipartUpload
```

不改变业务状态机。

---

# 42. 一个文件默认一个 Worker

V1：

```text
Worker 1 → A.zip
Worker 2 → B.zip
Worker 3 → C.zip
```

不做文件内部并行。

未来大文件：

```text
A.zip
 ├── part 1
 ├── part 2
 ├── part 3
 └── part 4
```

再启用 multipart。

---

# 43. 并发控制

不采用：

```text
unlimited threads
```

而采用：

> 有界、可配置并发。

至少：

```text
globalUploadConcurrency
globalGatewayConcurrency
```

以及：

```text
directory.maxConcurrency
```

例如：

```yaml
worker:
  upload:
    maxConcurrency: 8

  gateway:
    maxConcurrency: 8
```

目录：

```yaml
maxConcurrency: 4
```

最终实际并发：

```text
min(
    global limit,
    directory limit
)
```

---

# 44. 为什么要限制并发

即使每天只有：

```text
5GB
```

也可能出现：

```text
1000 files
```

短时间集中出现。

无限并发会导致：

```text
SMB
 ↓
NAS IO 爆炸

S3
 ↓
connection pool 爆炸

Gateway
 ↓
请求爆炸

JVM
 ↓
线程/socket 激增
```

所以：

> “尽可能快”应该通过**足够高但有上限的并发**实现。

---

# 45. Scheduler 与 Worker 解耦

Scanner 不应该直接：

```java
upload(file);
```

而应该：

```text
Scanner
  ↓
DB
  ↓
Dispatcher
  ↓
Worker
```

Scanner 负责：

> 发现事实。

Worker 负责：

> 执行业务动作。

这样 Scanner 不会被：

```text
S3 慢
Gateway 慢
SFTP 慢
```

拖住。

---

# 46. Dispatcher

Dispatcher 定期从 PostgreSQL 查询：

```text
READY
WAITING_RETRY
```

以及：

```text
next_retry_at <= now()
```

然后提交到：

```text
Bounded Executor
```

Executor：

```text
queue capacity = N
```

避免：

```text
DB 10000 tasks
 ↓
全部加载 JVM memory
```

应该：

> 小批量拉取 + 有界队列。

---

# 47. 背压

如果：

```text
Worker queue full
```

Dispatcher：

> 暂停继续拉取。

而不是继续：

```text
SELECT 10000
```

这样系统自然形成：

```text
NAS
 ↓
DB
 ↓
bounded queue
 ↓
workers
```

数据库承担 backlog。

---

# 48. Gateway Worker

流程：

```text
claim task
 ↓
create attempt
 ↓
call Gateway
 ↓
success?
 ├── YES → DELIVERED
 └── NO  → WAITING_RETRY
```

成功事务：

```text
BEGIN

attempt.success = true

task.status = DELIVERED
task.completed_at = now()

COMMIT
```

---

# 49. Gateway 失败

```text
BEGIN

attempt.success = false

task.retry_count += 1
task.next_retry_at = calculateBackoff(...)
task.status = WAITING_RETRY

COMMIT
```

异常信息：

```text
error_code
error_message
http_status
gateway_request_id
```

全部进入 attempt。

---

# 50. 不删除失败任务

无论 Gateway：

```text
1次失败
10次失败
1000次失败
```

都不删除：

```text
S3 object
DB task
DB history
```

保持：

```text
WAITING_RETRY
```

直到成功。

---

# 51. 人工强制重试

第一版没有前端。

但数据库模型和 Service 层支持：

```text
forceRetry(taskId)
```

逻辑：

```text
WAITING_RETRY
    ↓
READY
```

同时：

```text
next_retry_at = now()
```

并记录 audit。

未来可以直接暴露：

```http
POST /admin/transfers/{id}/retry
```

无需修改核心模型。

---

# 52. Audit

除了 attempt，还建议保留状态迁移审计。

例如：

```text
task #100

DISCOVERED
 ↓
STABILITY_CHECK
 ↓
READY
 ↓
UPLOADING
 ↓
S3_UPLOADED
 ↓
GATEWAY_DELIVERING
 ↓
WAITING_RETRY
 ↓
GATEWAY_DELIVERING
 ↓
DELIVERED
```

可以单独：

```text
transfer_event
```

记录：

```text
task_id
from_status
to_status
reason
operator
created_at
```

这对一年内故障追踪非常有价值。

---

# 53. 状态迁移必须集中管理

不要在代码各处：

```java
task.setStatus(...)
```

应该：

```java
taskStateMachine.transition(
    task,
    TargetState.READY,
    reason
);
```

统一验证：

```text
STABILITY_CHECK → READY       ✓
READY           → UPLOADING   ✓
UPLOADING       → DELIVERED   ✗
```

避免业务逻辑逐渐失控。

---

# 54. 数据保留

业务数据：

```text
file
file_version
transfer_task
transfer_attempt
transfer_event
```

保存：

> 1 年。

建议通过定时 maintenance job：

```text
每小时 / 每天
```

清理：

```text
created_at < now() - 1 year
```

但必须考虑 FK 顺序：

```text
transfer_event
transfer_attempt
transfer_task
file_version
file
```

逐级删除。

---

# 55. 不建议 PostgreSQL 保存应用日志

PostgreSQL：

> 保存业务状态、审计、attempt。

应用日志：

> 使用正常日志系统。

例如：

```text
stdout
 ↓
日志采集
 ↓
Loki / ELK / Splunk
```

不要把：

```text
INFO
DEBUG
stacktrace
```

塞进 PostgreSQL。

---

# 56. 敏感信息

PostgreSQL 不保存：

```text
S3 Secret Key
Gateway AppKey
```

application.yml：

```text
非敏感业务配置
```

环境变量：

```text
S3_ACCESS_KEY
S3_SECRET_KEY
GATEWAY_APP_ID
GATEWAY_APP_KEY
```

生产环境应由部署系统注入。

例如：

```text
systemd Environment
Docker environment
K8s Secret
```

虽然 V1 不使用 K8s，也不妨碍以后迁移。

---

# 57. 日志设计

所有日志必须带：

```text
taskId
fileVersionId
fileId
directoryId
customerSpace
filePath
```

例如：

```text
INFO
taskId=100
fileVersionId=20
customerSpace=customer-a
file=/order/A.zip
event=S3_UPLOAD_SUCCESS
durationMs=231
```

Gateway：

```text
INFO
taskId=100
event=GATEWAY_SUCCESS
gatewayRequestId=xxx
```

这样运维可以通过 taskId 串联整个过程。

---

# 58. Metrics

至少：

## Scanner

```text
scan_total
scan_success_total
scan_failure_total
scan_duration_seconds
files_discovered_total
files_stable_total
files_cancelled_total
```

## Upload

```text
upload_total
upload_success_total
upload_failure_total
upload_duration_seconds
upload_bytes_total
```

## Gateway

```text
gateway_request_total
gateway_success_total
gateway_failure_total
gateway_duration_seconds
gateway_retry_total
```

## Task

```text
task_ready
task_uploading
task_waiting_retry
task_delivering
task_delivered
task_cancelled
```

---

# 59. 最重要的告警

建议：

### 1. 长时间未完成任务

```text
WAITING_RETRY > X hours
```

### 2. Gateway 连续失败

```text
某 customer 过去 10 次全部失败
```

### 3. Scanner 异常

```text
目录连续 N 次扫描失败
```

### 4. backlog

```text
READY task 数量持续增长
```

### 5. Worker 异常

```text
stale lease 数量 > threshold
```

### 6. S3 上传异常

```text
upload failure rate > threshold
```

---

# 60. 运维查询模型

运维查询一个文件：

```text
A.zip
```

应该能看到：

```text
File
 ├── NAS
 ├── Directory
 ├── Path
 │
 ├── Version 1
 │    ├── size
 │    ├── mtime
 │    └── DELIVERED
 │
 ├── Version 2
 │    ├── size
 │    ├── mtime
 │    └── WAITING_RETRY
 │
 └── Version 3
      ├── size
      ├── mtime
      └── READY
```

并能展开：

```text
Attempt #1
Attempt #2
Attempt #3
```

最终做到：

> “这个文件到底有没有送到客户？”

可以直接回答。

---

# 61. 一次完整成功流程

```text
NAS
 │
 │ scan
 ▼
发现 A.zip
 │
 ▼
DB file
 │
 ▼
file_version
 │
 │ 第二次扫描仍然相同
 ▼
READY
 │
 ▼
claim
 │
 ▼
UPLOADING
 │
 ▼
S3 PUT
 │
 ▼
S3_UPLOADED
 │
 ▼
GATEWAY_DELIVERING
 │
 ▼
Gateway
 │
 ▼
SFTP
 │
 ▼
success
 │
 ▼
DELIVERED
```

---

# 62. 文件修改流程

```text
A.zip v1
 │
 ▼
DELIVERED
 │
 │ NAS size/mtime changed
 ▼
发现 v2
 │
 ▼
STABILITY_CHECK
 │
 │ 两次一致
 ▼
READY
 │
 ▼
等待 v1 完成
 │
 ▼
S3 overwrite
 │
 ▼
Gateway
 │
 ▼
SFTP
 │
 ▼
DELIVERED
```

---

# 63. Gateway 失败流程

```text
S3 uploaded
      │
      ▼
Gateway
      │
      X
      │
      ▼
WAITING_RETRY
      │
      │ next_retry_at
      ▼
Dispatcher
      │
      ▼
Gateway
      │
      X
      │
      ▼
WAITING_RETRY
      │
      ...
      │
      ▼
Gateway success
      │
      ▼
DELIVERED
```

---

# 64. JVM Crash 流程

## Crash during S3

```text
UPLOADING
   │
   X JVM crash
   │
restart
   │
lease expired
   │
READY
   │
re-upload
```

---

## Crash during Gateway

```text
GATEWAY_DELIVERING
   │
   X JVM crash
   │
restart
   │
lease expired
   │
WAITING_RETRY
   │
Gateway retry
```

即使原请求其实成功：

> 允许重复投递。

---

# 65. Scanner Crash

如果 Scanner：

```text
扫描 1000 个文件
```

扫描到第：

```text
500
```

时 JVM crash。

重启后重新扫描。

已经写入 DB 的：

```text
file_version
transfer_task
```

由于唯一约束：

```text
ON CONFLICT DO NOTHING
```

不会产生重复任务。

剩余 500 个继续发现。

因此：

> Scanner 不需要 checkpoint 才能保证正确性。

---

# 66. 数据库事务原则

核心原则：

> **数据库状态更新和业务事实必须尽可能原子化。**

例如创建任务：

```text
file
file_version
transfer_task
```

同一个事务。

但是：

```text
S3 PUT
```

和：

```text
DB COMMIT
```

无法组成真正的分布式事务。

因此不能追求：

> S3 与 PostgreSQL Exactly Once。

而采用：

> PostgreSQL 状态机 + At-least-once retry + 幂等/覆盖。

---

# 67. 为什么这个系统不需要分布式事务

系统最终一致性目标是：

```text
NAS
 ↓
DB
 ↓
S3
 ↓
Gateway
 ↓
SFTP
```

每个外部系统都可能失败。

因此：

> 不追求跨系统 ACID。

而采用：

```text
Durable State
+
Retry
+
At-least-once
+
Idempotent/overwrite
```

这是更适合该业务的方案。

---

# 68. Exactly-once 的处理

本系统明确：

```text
At-least-once
```

不保证：

```text
Exactly-once
```

因为存在：

```text
Gateway success
 ↓
response lost
```

这种无法判断的情况。

因此：

```text
retry
```

可能产生重复投递。

这是业务明确允许的。

---

# 69. 关键不变量

系统实现必须始终保证以下不变量：

### 不变量 1

一个 file version：

> 最多只有一个 transfer_task。

由：

```text
UNIQUE(file_version_id)
```

保证。

### 不变量 2

一个 file path：

> 同一时刻最多一个版本进入实际处理阶段。

由：

```text
file_id serialization
```

保证。

### 不变量 3

任务失败：

> 不允许因为失败自动删除任务。

### 不变量 4

S3 上传成功但 DB 状态未知：

> 重启后允许重新上传。

### 不变量 5

Gateway 状态未知：

> 重试。

### 不变量 6

Scanner 重复运行：

> 不得产生重复 task。

---

# 70. Java 21 技术建议

核心技术：

```text
JDK 21
Spring Boot
Spring JDBC / JPA
PostgreSQL
S3 SDK
SMB/CIFS client
HTTP Client
Micrometer
```

并发建议使用：

```text
ThreadPoolExecutor
```

配合：

```text
BoundedBlockingQueue
```

而不是：

```text
Executors.newCachedThreadPool()
```

避免无限扩张。

---

# 71. Virtual Threads 是否使用

JDK 21 已经支持 Virtual Threads。

对于：

```text
SMB IO
HTTP Gateway
S3 HTTP
```

这种 IO-bound 场景，可以考虑：

```text
Executors.newVirtualThreadPerTaskExecutor()
```

但仍然需要：

> Semaphore / Rate Limiter

控制业务并发。

例如：

```text
Virtual Threads
      │
      ▼
Semaphore(8)
      │
      ▼
S3
```

所以：

> Virtual Thread ≠ 无限并发。

V1 可以采用：

```text
Virtual Threads
+
Semaphore
```

或者传统 bounded platform thread pool。

如果团队对 Virtual Threads 不熟，第一版使用传统有界线程池也完全合理。

---

# 72. 推荐的并发结构

```text
Scanner
   │
   ▼
PostgreSQL
   │
   ▼
Dispatcher
   │
   ├── Upload Semaphore
   │
   └── Gateway Semaphore
```

例如：

```text
uploadConcurrency = 8
gatewayConcurrency = 8
```

每个目录：

```text
directoryConcurrency = 2~4
```

最终按更严格限制执行。

---

# 73. SMB 注意事项

应用不要：

```text
一次把整个目录文件加载到内存。
```

应该：

```text
Files.newInputStream(...)
      ↓
stream
      ↓
S3
```

对于 10MB 文件，内存压力不大。

但仍建议：

> Streaming upload。

不要：

```java
byte[] bytes = Files.readAllBytes(...)
```

---

# 74. 文件读取

推荐：

```text
SMB/NAS
 ↓
InputStream
 ↓
S3 SDK request body
```

如果 S3 SDK 要求可重试读取，需要特别处理：

> retry 时重新打开 NAS 文件。

不要假设原 InputStream 可以无限 rewind。

因此一次 upload attempt：

```text
open SMB file
 ↓
stream to S3
 ↓
close
```

失败后：

```text
重新打开 SMB file
```

---

# 75. S3 上传失败时

例如：

```text
读取 NAS
 ↓
S3 network timeout
```

不要立即进入 Gateway。

任务：

```text
UPLOADING
 ↓
retry
```

直到：

```text
S3 uploaded
```

只有 S3 成功以后：

```text
GATEWAY_DELIVERING
```

才允许执行。

---

# 76. S3 成功后不要立即认为整个任务完成

必须：

```text
S3_UPLOAD_SUCCESS
```

再：

```text
Gateway
```

因为：

```text
S3 成功 ≠ SFTP 成功
```

最终：

```text
DELIVERED
```

只能在 Gateway 明确成功后设置。

---

# 77. S3 覆盖与同文件串行是一个核心设计

最终模型：

```text
                 A.zip
                   │
          ┌────────┴────────┐
          │                 │
         v1                v2
          │                 │
          ▼                 │
       S3 PUT               │
          │                 │
          ▼                 │
      Gateway               │
          │                 │
          ▼                 │
       DELIVERED            │
                            ▼
                         S3 PUT
                            │
                            ▼
                         Gateway
```

保证不会出现：

```text
v1 Gateway
v2 overwrite S3
v1 Gateway 从 S3 读到 v2
```

---

# 78. 配置修改

V1：

```text
application.yml
```

修改后：

```text
restart
```

即可。

已有：

```text
READY
UPLOADING
WAITING_RETRY
```

的任务：

> 使用任务创建时的配置 snapshot。

新任务：

> 使用新配置。

---

# 79. 第一版不需要的东西

根据当前规模，明确不引入：

```text
Kafka
RabbitMQ
Redis
Kubernetes
Distributed Lock
Distributed Transaction
Object Versioning
Content Hash
Multipart Upload
Web Admin
```

除非未来需求发生变化。

这样可以保持系统：

> 简单、可靠、可运维。

---

# 80. 未来演进路线

## V1

```text
Single JVM
+
PostgreSQL
+
SMB
+
S3
+
Gateway
```

---

## V2：Admin API

增加：

```text
GET /transfers
GET /transfers/{id}
POST /transfers/{id}/retry
GET /files/{id}/history
```

---

## V3：Archive/Delete

成功：

```text
DELIVERED
 ↓
Archive
```

例如：

```text
/mnt/nas/archive/...
```

---

## V4：大文件

启用：

```text
S3 Multipart Upload
```

---

## V5：水平扩展

如果未来需要：

```text
instance-1
instance-2
instance-3
```

当前：

```text
FOR UPDATE SKIP LOCKED
+
lease
```

可以自然演进。

---

## V6：MQ

只有 PostgreSQL task queue 成为瓶颈时，再考虑：

```text
Kafka
RabbitMQ
```

而不是一开始引入。

---

# 81. 容量估算

当前：

```text
1000 files/day
365 days
```

一年：

```text
365,000 file versions
```

如果每个 task：

```text
~1KB
```

任务数据只有几百 MB 级别。

即使考虑：

```text
attempt
event
index
```

PostgreSQL 仍然非常轻松。

单目录：

```text
10,000 files
```

对于 PostgreSQL：

> 完全不是压力。

真正需要控制的是：

```text
NAS IO
S3 connection
Gateway concurrency
```

而不是数据库容量。

---

# 82. 推荐索引

## file

```sql
CREATE UNIQUE INDEX uk_file_path
ON file(nas_id, directory_id, relative_path);
```

## file_version

```sql
CREATE UNIQUE INDEX uk_file_version_fingerprint
ON file_version(file_id, fingerprint);
```

## task claim

```sql
CREATE INDEX idx_task_ready
ON transfer_task(status, next_retry_at, created_at);
```

## stale task recovery

```sql
CREATE INDEX idx_task_lease
ON transfer_task(status, lease_until);
```

## history

```sql
CREATE INDEX idx_attempt_task
ON transfer_attempt(transfer_task_id, attempt_no);
```

---

# 83. 建议不要过度索引

当前：

```text
365k versions/year
```

数据量不大。

但仍然避免给：

```text
error_message
file_path
customer_space
```

全部建立索引。

优先索引：

> 真正参与任务扫描和恢复的字段。

---

# 84. 系统启动恢复

应用启动：

```text
1. Load configuration
2. Validate configuration
3. Connect PostgreSQL
4. Validate S3
5. Validate Gateway
6. Recover stale tasks
7. Start retry dispatcher
8. Start directory schedulers
9. Start workers
```

顺序建议：

```text
DB
 ↓
Recovery
 ↓
Worker
 ↓
Scanner
```

不要 Scanner 先启动。

否则可能：

```text
新扫描任务
```

和：

```text
旧任务 recovery
```

同时大量抢 worker。

---

# 85. Graceful Shutdown

收到：

```text
SIGTERM
```

执行：

```text
1. Stop new scans
2. Stop accepting new tasks
3. Wait active workers
4. Finish current upload if possible
5. Release lease
6. Shutdown
```

如果超时强杀：

```text
lease
```

负责下一次恢复。

---

# 86. 故障场景矩阵

| 场景 | 处理 |
|---|---|
| NAS 不可访问 | Scanner failure，下一周期 retry |
| NAS 文件消失 | 未开始读取 → CANCELLED |
| 上传中 NAS 删除 | 当前 upload 继续 |
| 文件变化 | 创建新 file_version |
| S3 上传失败 | retry |
| S3 上传成功后 crash | 重启重新上传 |
| Gateway 失败 | retry |
| Gateway timeout | retry |
| Gateway 已成功但 response 丢失 | retry，允许重复 |
| JVM crash | lease recovery |
| DB 暂时不可用 | 服务等待/失败恢复 |
| Gateway 长期不可用 | 无限 retry |
| 配置修改 | 新任务使用新配置 |
| Scanner 重复扫描 | DB unique constraint 防重复 |
| 同文件多版本 | 同 path 串行 |
| S3 object 被覆盖 | 正常，最终一致 |

---

# 87. 最终一致性定义

这个系统的最终一致性目标：

```text
稳定文件
   ↓
被数据库记录
   ↓
进入 transfer task
   ↓
S3 最终存在最新版本
   ↓
Gateway 最终成功
   ↓
客户 SFTP 最终存在对应文件
```

只要：

```text
NAS 文件存在
S3 正常
Gateway 最终恢复
SFTP 最终恢复
```

系统就会持续 retry。

因此：

> 临时故障不会导致文件永久丢失。

---

# 88. 最核心的设计原则

整个系统实际上可以浓缩成 7 条：

### ① PostgreSQL 是 Source of Truth

任何文件是否处理、处理到哪里，都以 DB 为准。

### ② Scanner 只负责发现

Scanner 不负责可靠执行上传。

### ③ Worker 只负责执行

Worker 不负责判断文件是否是新版本。

### ④ DB Unique Constraint 防重复

不能只相信 Java 代码。

### ⑤ Lease 保证 Crash Recovery

不需要依赖人工恢复。

### ⑥ At-least-once 优先于 Exactly-once

宁可重复，不允许丢失。

### ⑦ 同一路径串行、不同文件并行

既保证 S3/Gateway 版本语义，又保证整体吞吐。

---

# 89. 最终推荐架构

```text
                              ┌───────────────────┐
                              │ application.yml   │
                              │ + ENV Secrets     │
                              └─────────┬─────────┘
                                        │
                                        ▼
┌──────────────────────────────────────────────────────────────┐
│                        Transfer Application                  │
│                                                              │
│  ┌──────────────┐                                            │
│  │   Scheduler  │                                            │
│  └──────┬───────┘                                            │
│         │                                                    │
│         ▼                                                    │
│  ┌──────────────┐       ┌───────────────────────────────┐   │
│  │ SMB Scanner  │──────▶│          PostgreSQL           │   │
│  └──────────────┘       │                               │   │
│                         │ file                          │   │
│                         │ file_version                  │   │
│                         │ transfer_task                 │   │
│                         │ transfer_attempt              │   │
│                         │ transfer_event                 │   │
│                         └──────────────┬────────────────┘   │
│                                        │                    │
│                                 claim + lease               │
│                                        │                    │
│                              ┌─────────▼─────────┐          │
│                              │    Dispatcher     │          │
│                              └─────────┬─────────┘          │
│                                        │                    │
│                        ┌───────────────┴──────────────┐     │
│                        │                              │     │
│                 Upload Workers                 Gateway Workers
│                        │                              │     │
│                        ▼                              ▼     │
└────────────────────────┼──────────────────────────────┼─────┘
                         │                              │
                         ▼                              │
                    ┌─────────┐                         │
                    │   S3    │◀────────────────────────┘
                    └────┬────┘
                         │
                         │ Gateway
                         ▼
                  ┌──────────────┐
                  │ B2B Gateway  │
                  └──────┬───────┘
                         │
                         ▼
                    Customer SFTP
```

---

# 90. V1 开发优先级

建议开发顺序：

```text
P0
├── PostgreSQL schema
├── File scanner
├── Stability check
├── File version detection
├── Task state machine
├── Task claim / lease
├── S3 upload
├── Gateway API
├── Retry
└── Crash recovery

P1
├── Attempt history
├── Event audit
├── Metrics
├── Structured logging
└── Alerting

P2
├── Admin API
├── Manual retry API
└── Archive/Delete

P3
├── Multipart
├── Horizontal scaling
└── MQ
```

---

# 91. 一个最终需要特别注意的实现细节

当前方案里最大的技术风险不是 PostgreSQL，也不是 S3，而是：

> **同一个 NAS 文件版本的生命周期与 S3/Gateway 调用之间的竞态。**

因此开发时必须严格保证：

```text
同一 file_id：

Version N
    ↓
S3 Upload
    ↓
Gateway Delivery
    ↓
完成
    ↓
Version N+1
    ↓
S3 Upload
```

不能允许：

```text
Version N
    ↓
Gateway Delivery ────────────────┐
                                  │
Version N+1                       │
    ↓                             │
覆盖 S3                           │
    ↓                             │
Gateway Delivery                  │
                                  ▼
                         Gateway(N) 读取到 N+1
```

这是本系统最重要的并发控制点之一。

---

# 92. V1 验收标准

系统上线前至少应通过以下测试：

### 正常流程

```text
NAS → S3 → Gateway → SFTP
```

### 文件写入中

```text
size/mtime continuously changing
```

不能上传。

### 两次稳定

```text
same size + mtime
```

进入 READY。

### 文件删除

```text
READY → CANCELLED
```

### 文件变化

```text
v1 → v2
```

产生新的 file_version。

### S3 失败

自动 retry。

### Gateway 失败

自动 retry。

### Gateway timeout

自动 retry。

### Gateway success + response lost

重复调用，最终成功。

### JVM kill -9 during S3

重启后恢复。

### JVM kill -9 during Gateway

重启后恢复。

### Scanner 重复执行

不能产生重复 task。

### 同文件 v1/v2

不能并行处理。

### 不同文件

可以并行。

### 配置修改

旧任务不受影响。

### Gateway 永久不可用

任务不能消失，持续 retry。

---

# 93. 结论

V1 最终采用：

```text
JDK 21
+
PostgreSQL
+
SMB/CIFS
+
S3
+
B2B Gateway
+
Customer SFTP
```

不引入 MQ。

核心可靠性模型：

```text
PostgreSQL Source of Truth
        +
Persistent State Machine
        +
Unique Constraints
        +
Worker Lease
        +
Crash Recovery
        +
At-least-once
        +
Infinite Retry
        +
Bounded Concurrency
```

业务最终语义：

> **文件可以重复，但不能因为中转应用的故障而永久丢失。**

同时：

> **同一路径版本串行，不同文件高度并行；S3 只保留当前版本，PostgreSQL 保留一年历史。**

这套设计对于目前的 **2 NAS / 20 目录 / 1000 文件/天 / 5GB/天 / 单文件 ≤10MB / 单机**规模明显足够，而且没有为了未来可能的规模过早引入 MQ、分布式系统等复杂组件。