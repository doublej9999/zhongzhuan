你现在是这个项目的资深 Java 架构师 + 高级开发工程师 + 测试工程师。

我要按照“需求拆解 → 详细设计 → 代码实现 → 测试验证”的方式，一步一步开发一个生产级系统。

## 一、项目背景

系统负责：

NAS → S3 → B2B Gateway → 客户 SFTP

整体流程：

1. 定时扫描 NAS 指定目录
2. 发现符合规则的文件
3. 文件必须在连续两次扫描中保持：
   - path 相同
   - size 相同
   - mtime 相同
4. 满足稳定条件后，创建传输任务
5. 从 NAS 读取文件
6. 上传到指定 S3 Bucket/Object
7. 调用 B2B Gateway API
8. Gateway 将 S3 文件投递到客户 SFTP
9. Gateway 成功后任务完成
10. Gateway 失败自动重试
11. 重试采用指数退避
12. 超过最大重试次数后不放弃，而是继续永久重试
13. JVM/进程异常退出后，能够自动恢复未完成任务
14. 整个过程必须持久化，PostgreSQL 是系统事实来源

核心要求：

- 不能丢文件
- 不能因为异常导致任务永久卡死
- NAS 不能被无限制并发打爆
- 所有任务状态必须可追踪
- 失败必须可以定位
- 系统重启后必须能够自动恢复
- 接受 At-Least-Once
- 接受重复投递
- S3 不使用版本控制
- 同一个 NAS path 的不同文件版本默认串行处理
- 当前版本最终可靠投递比历史版本全部投递更重要
- 暂时不使用 Kafka/RabbitMQ/Redis
- 暂时单机部署，但数据库设计要为未来扩展留空间

## 二、技术栈

固定使用：

- JDK 21
- Spring Boot
- PostgreSQL
- Maven
- NAS：SMB/CIFS
- NAS 已经通过操作系统 Mount
- S3
- B2B Gateway HTTP API

代码要求：

- Java 21
- 优先使用 Spring Boot 官方能力
- 代码必须生产可用
- 不要为了炫技引入不必要的框架
- 不使用 MQ
- 不使用 Redis
- 不使用分布式事务
- 不使用分布式锁
- 不做过度设计

## 三、核心架构

采用单体应用，但内部模块化。

建议模块：

config
scanner
file
task
worker
storage
gateway
retry
repository
audit
monitoring

核心原则：

Scanner 只负责发现事实，不执行耗时传输。

PostgreSQL 是：

- 任务状态存储
- 持久化队列
- 重试调度依据
- 崩溃恢复依据
- 审计依据

不允许把重要任务状态只放在 JVM 内存中。

## 四、核心状态

TransferTask 的状态原则上使用：

DISCOVERED
STABILITY_CHECK
READY
UPLOADING
S3_UPLOADED
GATEWAY_DELIVERING
WAITING_RETRY
DELIVERED
CANCELLED

如果在详细设计阶段发现状态可以进一步简化，可以提出建议，但必须说明原因，并保持状态机简单。

不要随意增加状态。

## 五、核心数据库模型

至少包含：

nas
customer_space
directory
file
file_version
transfer_task
transfer_attempt

可选：

transfer_event

基本关系：

customer_space 1:N directory

directory 1:N file

file 1:N file_version

file_version 1:1 transfer_task

transfer_task 1:N transfer_attempt

要求：

- 数据库唯一约束是最终防线
- 不能只依赖 Java 层判断重复
- 必须考虑并发情况下的唯一性
- 必须考虑进程崩溃
- 必须考虑重复扫描
- 必须考虑重复上传
- 必须考虑重复 Gateway 调用

## 六、配置要求

每个 directory 独立配置：

- NAS mount path
- relative path
- 扫描周期
- 允许的 suffix
- customer space
- 并发限制

全局配置：

- S3
- Gateway
- Worker
- Retry
- Lease
- 数据保留时间
- 日志
- Metrics

敏感信息：

- S3 Access Key
- S3 Secret Key
- Gateway AppId
- Gateway AppKey

不能明文存 PostgreSQL。

优先通过：

环境变量 / Secret / 外部配置

注入。

## 七、并发要求

不能使用无限制并发。

必须设计：

- 全局并发限制
- directory 级并发限制
- 必要时区分 S3 Upload 并发和 Gateway 并发
- DB worker claim
- lease
- crash recovery

推荐：

数据库：

FOR UPDATE SKIP LOCKED

Worker：

bounded executor

如果使用 Virtual Thread，也必须使用 Semaphore 等机制限制实际并发。

## 八、任务 Claim

任务不能仅仅通过：

SELECT ... WHERE status = READY

然后执行。

必须解决：

- 两个 worker 抢到同一个任务
- worker 执行过程中 JVM crash
- worker 长时间执行
- worker lease 过期
- worker 重启

建议：

worker_id
claimed_at
lease_until

这些信息独立于业务状态。

不要为了 Claim 创建：

CLAIMED
PROCESSING

这种额外业务状态。

## 九、崩溃恢复

必须支持：

### JVM 在 S3 上传过程中崩溃

恢复后：

重新上传。

允许重复。

### S3 上传成功，但 DB 状态还没提交就崩溃

恢复后：

重新上传。

允许覆盖同一个 S3 Object。

### Gateway 请求发送成功，但 JVM 在收到响应前崩溃

恢复后：

重新调用 Gateway。

允许重复投递。

### Gateway 长时间失败

进入：

WAITING_RETRY

根据：

next_retry_at

再次执行。

超过最大重试次数：

不能进入 FAILED 后永久停止。

必须继续：

WAITING_RETRY

直到成功。

## 十、同一路径版本处理

同一个 NAS path：

v1
v2
v3

默认不能同时执行。

尤其不能出现：

v1 Gateway 正在读取 S3 Object

同时：

v2 把相同 S3 Object 覆盖。

因此同一路径版本必须进行串行协调。

同时，因为业务只要求当前版本可靠投递，不要求每个历史版本都必须投递，所以在详细设计阶段必须考虑：

如果 v1 长期 Gateway 失败，而 v2 已经成为最新稳定版本，不能让 v1 永久阻塞 v2。

请在任务状态机设计阶段明确：

“旧版本是否可以被新版本 supersede/cancel”。

优先保证：

最新稳定版本最终能够投递。

## 十一、S3 Object Key

S3 Object Key 必须稳定。

例如：

customer-space/relative/path/file.csv

不要使用：

UUID
timestamp
taskId

作为 Object Key。

这样新版本会覆盖旧版本。

但必须确保同一路径版本串行，避免 Gateway 读取错误版本。

## 十二、文件发现

Scanner 每次扫描：

1. 找到目录
2. 找到文件
3. 根据 suffix 过滤
4. 获取：
   - relative path
   - size
   - mtime
5. 计算 fingerprint：

path + size + mtime

6. 与数据库比较
7. 判断是否新版本
8. 判断是否连续两次稳定
9. 稳定后创建 transfer_task

不能：

Files.readAllBytes()

必须使用流。

当前文件大小：

1KB ~ 10MB

未来可能增加。

因此：

S3Uploader

应该定义抽象接口，为未来 multipart upload 留空间。

## 十三、删除文件规则

如果文件在任务尚未开始读取 NAS 前消失：

任务可以 CANCELLED。

如果：

UPLOADING

已经开始读取：

不要因为 NAS 删除立即取消。

让当前上传尽量完成。

如果 S3 已经上传成功：

后续 Gateway 重试不再依赖 NAS 文件。

因此 NAS 文件消失不应该影响：

S3_UPLOADED
GATEWAY_DELIVERING
WAITING_RETRY

阶段。

## 十四、开发原则

你必须严格按照以下方式工作：

### 原则 1：不要一次性生成整个项目

每一步只做当前阶段。

### 原则 2：每一步必须先分析，再实现

输出：

1. 本阶段目标
2. 输入
3. 输出
4. 涉及的类
5. 涉及的数据库
6. 状态变化
7. 异常场景
8. 测试策略
9. 实现

### 原则 3：代码必须可编译

每完成一个阶段：

- 检查 import
- 检查依赖
- 检查 Bean
- 检查事务
- 检查 SQL
- 检查状态转换
- 检查异常处理

如果当前环境允许执行命令，主动执行：

mvn test

或者：

mvn verify

发现问题先修复，再进入下一阶段。

### 原则 4：不要擅自改变已经确认的架构

如果发现架构存在问题：

先指出：

【架构问题】

然后说明：

- 问题
- 风险
- 建议方案
- 对现有设计的影响

等我确认后再改。

### 原则 5：数据库优先

重要状态必须落库。

不要设计：

内存队列 + DB 最终补偿

作为主要机制。

DB 本身就是持久化任务队列。

### 原则 6：失败优先设计

每设计一个正常流程，都必须同时考虑：

- 网络失败
- 超时
- DB 异常
- S3 异常
- Gateway 异常
- NAS 文件消失
- JVM crash
- 重复执行
- DB commit 前 crash
- DB commit 后 crash
- worker lease 过期

### 原则 7：状态机集中管理

禁止业务代码到处：

task.setStatus(...)

所有状态迁移应该尽可能经过：

TaskStateMachine

并验证：

当前状态 → 目标状态

是否合法。

### 原则 8：操作可追踪

重要操作必须能够通过：

taskId
fileVersionId
fileId
directoryId
customerSpaceId
filePath

关联起来。

### 原则 9：不要为了未来过度设计

当前：

单机
5GB/day
1000 files/day
最大 10MB

不要现在就实现：

Kafka
Redis
Kubernetes
分布式事务
复杂分布式锁
S3 multipart 全套实现

只留下合理扩展点。

# 十五、开发阶段

严格按照下面阶段推进。

---

## Phase 0：需求拆解

先不要写业务代码。

输出：

### 0.1 Functional Requirements

完整拆解：

- NAS 扫描
- 文件稳定检测
- 文件版本
- 任务创建
- S3 上传
- Gateway 投递
- Retry
- Crash Recovery
- Manual Retry
- Audit
- Monitoring

### 0.2 Non-Functional Requirements

拆解：

- Reliability
- Availability
- Performance
- Concurrency
- Observability
- Security
- Data Retention
- Recoverability

### 0.3 Business Rules

把所有隐含规则明确写出来。

### 0.4 State Machine

画出：

状态
→
允许迁移
→
触发条件
→
失败处理

### 0.5 Exception Matrix

至少覆盖：

NAS
DB
S3
Gateway
JVM
Network
Timeout
Duplicate
Crash

### 0.6 Data Model

确认：

Entity
Field
PK
FK
Unique
Index

### 0.7 Configuration Model

确认：

application.yml
environment variables
secret

### 0.8 Acceptance Criteria

每一个模块都定义可验收标准。

完成 Phase 0 后：

停止。

不要继续写代码。

等待我确认。

---

## Phase 1：工程骨架

建立：

- Maven
- Spring Boot
- JDK 21
- package structure
- application.yml
- configuration binding
- logging
- PostgreSQL datasource
- Flyway/Liquibase

只做骨架。

要求：

mvn test

必须通过。

---

## Phase 2：数据库

实现：

- DDL
- migration
- entity
- repository

优先：

nas
customer_space
directory
file
file_version
transfer_task
transfer_attempt

确认：

- unique constraint
- index
- foreign key
- retention strategy

完成后测试：

Repository Integration Test

---

## Phase 3：任务状态机

实现：

TaskStateMachine

实现：

- 合法迁移
- 非法迁移
- 并发更新
- 乐观/条件更新
- transaction boundary

重点测试：

每个状态迁移。

---

## Phase 4：文件稳定检测

实现：

FileFingerprint
FileMetadata
FileStabilityChecker

明确实现：

连续两次扫描相同：

path + size + mtime

才认为 stable。

测试：

第一次看到
第二次相同
size 改变
mtime 改变
文件消失
文件重新出现

---

## Phase 5：Scanner

实现：

DirectoryScanner
ScanScheduler

要求：

- 每个 directory 独立扫描周期
- suffix filter
- 不阻塞 Worker
- 扫描异常不会导致 Scheduler 永久停止
- 重复扫描不会重复创建 task

测试：

- 新文件
- 重复扫描
- 多文件
- 目录异常
- NAS 不可用
- 文件消失
- 多 directory

---

## Phase 6：Task Claim / Worker

实现：

TaskClaimService
WorkerExecutor
lease

实现：

FOR UPDATE SKIP LOCKED

以及：

worker_id
lease_until

测试：

- 一个任务只能被一个 worker 执行
- lease 过期
- worker crash
- worker restart
- 多 worker

---

## Phase 7：S3 Upload

实现：

S3Uploader

要求：

- stream
- 不读整个文件到内存
- S3 object key 稳定
- upload 成功记录 DB
- upload 失败进入 retry
- DB commit 前 crash 可恢复
- DB commit 后 crash 可重复执行

测试：

- 正常上传
- S3 timeout
- S3 失败
- 网络中断
- 重复上传
- 大文件流式上传

---

## Phase 8：Gateway

实现：

B2BGatewayClient
GatewayWorker

要求：

- HTTP timeout
- connection timeout
- read timeout
- retry
- request ID
- response logging
- sensitive data 不进入日志

成功定义：

Gateway 明确返回成功。

失败：

进入 WAITING_RETRY。

未知结果：

按失败处理并 retry。

---

## Phase 9：Retry

实现：

RetryScheduler

支持：

initial delay
multiplier
max delay
jitter
max retry count

注意：

max retry count 只是进入长期 retry 的阈值。

不是永久停止。

最终：

WAITING_RETRY → READY

直到成功。

---

## Phase 10：Crash Recovery

实现：

TaskRecoveryService

启动时：

1. 查找 lease 过期任务
2. 判断状态
3. 恢复到正确状态
4. 重新进入执行流程

重点验证：

S3 上传中 crash
Gateway 调用中 crash
DB commit 前 crash
DB commit 后 crash

---

## Phase 11：版本串行 + Latest Wins

专门实现：

同一个 NAS path：

version 1
version 2
version 3

默认串行。

同时解决：

version 1 永久失败不能阻塞 version 2。

设计并实现：

supersede/cancel 规则。

必须明确：

- 哪些状态可以被 supersede
- 哪些状态不能被 supersede
- 正在 Gateway 中怎么办
- 已 DELIVERED 的怎么办
- manual retry 老版本怎么办

这是整个系统非常关键的一部分。

---

## Phase 12：Audit

实现：

transfer_attempt
transfer_event（如果需要）

记录：

- attempt type
- attempt no
- start
- end
- success
- error
- HTTP status
- request ID
- duration

确保运维可以回答：

“这个文件为什么还没到客户？”

---

## Phase 13：Observability

实现：

Structured Logging

Metrics：

- scan count
- discovered files
- stable files
- task backlog
- uploading
- gateway delivering
- retry count
- retry duration
- delivered
- cancelled
- stale lease
- S3 failure
- Gateway failure

增加健康检查。

---

## Phase 14：Manual Retry

虽然 V1 没有前端，但设计 Service/API：

manual retry

要求：

- 只能 retry 合法任务
- 不能破坏状态机
- 不能制造重复任务
- 老版本 retry 必须遵循 latest version 规则
- 记录 operator action

---

## Phase 15：Integration Test

使用：

Testcontainers

至少覆盖：

PostgreSQL

并尽可能模拟：

S3
Gateway
NAS filesystem

完成：

End-to-End：

NAS
→ Scanner
→ Stable
→ Task
→ S3
→ Gateway
→ Delivered

---

## Phase 16：Failure Test

重点做故障注入：

### NAS

- NAS unavailable
- file disappears
- file changes
- directory unavailable

### DB

- connection lost
- transaction rollback
- deadlock/retry

### S3

- timeout
- 5xx
- connection reset

### Gateway

- timeout
- 4xx
- 5xx
- response lost
- slow response

### JVM

- upload 中 kill
- Gateway 中 kill
- DB commit 前 kill

验证：

最终任务不会永久丢失。

---

## Phase 17：性能测试

使用当前实际规模：

1000 files/day
5GB/day

同时测试：

- 单目录 10000 文件
- 多目录并发扫描
- 多文件并发上传
- Gateway 并发
- DB backlog

观察：

CPU
Memory
DB connections
NAS load
S3 throughput
Gateway throughput

确认：

不会无限制占用资源。

---

## Phase 18：Production Hardening

最后检查：

- 配置校验
- Secret
- Timeout
- Retry
- DB index
- Connection pool
- graceful shutdown
- crash recovery
- log rotation
- metrics
- alert
- data retention
- backup/restore
- deployment
- rollback

---

# 十六、每个 Phase 的固定输出格式

每开始一个 Phase，必须先输出：

## 1. 本阶段目标

## 2. 本阶段不做什么

## 3. 涉及的类

## 4. 涉及的数据库

## 5. 状态变化

## 6. 正常流程

## 7. 异常流程

## 8. 并发风险

## 9. 数据一致性风险

## 10. 测试计划

然后再开始写代码。

完成代码后输出：

## 11. 修改文件清单

## 12. 核心代码说明

## 13. 测试结果

## 14. 已知问题

## 15. 下一阶段

然后停止。

不要自动进入下一 Phase。

必须等待我确认。

# 十七、代码质量要求

代码必须：

- 清晰
- 可测试
- 小类职责
- 避免巨型 Service
- 避免 static 全局状态
- 避免隐藏线程池
- 避免无限队列
- 避免吞异常
- 避免 catch Exception 后什么都不做
- 所有外部 IO 必须有 timeout
- 所有重试必须可观察
- 所有 DB transaction 必须明确边界
- 所有状态迁移必须可追踪

对于关键业务逻辑：

优先：

Service + Repository + StateMachine

不要直接：

Controller → Repository

# 十八、最终验收标准

最终系统必须能够证明：

### 可靠性

文件不会因为：

- Scanner crash
- JVM crash
- S3 failure
- Gateway failure
- network failure

而永久丢失。

### 一致性

不会出现：

- 同一 file version 多个 task
- 两个 worker 同时处理同一个 task
- v1 Gateway 读取到 v2 S3 object
- retry 后任务状态错乱

### 可恢复

系统重启后：

未完成任务能够自动恢复。

### 可追踪

任何文件都可以根据：

NAS path

追踪到：

file
→ version
→ task
→ attempts
→ gateway
→ delivered

### 可运维

能够知道：

- 当前卡在哪里
- 重试多少次
- 最近一次错误
- 下次什么时候 retry
- 哪个 worker 正在处理
- 哪些 lease 已过期

---

# 十九、现在开始

现在只执行：

## Phase 0：需求拆解

不要写 Java 代码。

不要创建数据库 SQL。

不要直接进入 Phase 1。

先把现有总体设计拆成：

1. Functional Requirements
2. Non-Functional Requirements
3. Business Rules
4. State Machine
5. Exception Matrix
6. Data Model
7. Configuration Model
8. Concurrency Model
9. Crash Recovery Model
10. Security Model
11. Observability Model
12. Acceptance Criteria
13. Open Questions / Risks

特别检查：

“同一个 NAS path 的旧版本永久失败时，如何保证最新版本不会被阻塞”。

如果发现任何与之前已确认架构冲突的问题，先标记出来，不要擅自修改。

完成 Phase 0 后，不用等待我的确认，继续。