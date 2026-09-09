-- =====================================================================
-- V1__init_schema.sql — Phase 2：核心数据模型
-- 依据：docs/phase-0/06-data-model.md（权威）；docs/phase-0/04/13 的【架构问题】裁决：
--
--   裁决记录（在 DDL 中落地，Phase 0 已登记、现按约定裁决）：
--   A2 file_version.version_no 落地（整体设计 §33 缺该列，§39 需要它）
--       → 列 version_no + UNIQUE(file_id, version_no)
--   A3 transfer_task 冗余 file_id / version_no（claim / 串行守卫 SQL 依赖）
--   A4 transfer_attempt 保留 success BOOLEAN，并新增 outcome CHECK
--       （SUCCESS / FAILURE / UNKNOWN，表达"结果未知"，A4 裁决）
--   A5 移除 file_version.status 列（双状态源风险裁决为"移除"）：
--       稳定语义 = stable_at IS NOT NULL；
--       唯一权威任务状态 = transfer_task.status（9 态 CHECK）
--
--   保留策略（确认口径，A7 / BR-55 / 06 §R10）：
--      仅清理终态（DELIVERED / CANCELLED）且超 1 年的数据；
--      非终态任务永不清理；清理作业由应用层按
--      transfer_event → transfer_attempt → transfer_task → file_version → file
--      子表先删的顺序执行（本迁移不包含清理调度器）。
--
-- 本文件为 Phase 2 授权交付的 DDL；幂等由 Flyway 版本控制保证，勿手工改动已应用版本。
-- =====================================================================

-- 1. nas：NAS 实例 ------------------------------------------------
CREATE TABLE nas (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(128)  NOT NULL,
    mount_path  VARCHAR(1024) NOT NULL,
    enabled     BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_nas_name UNIQUE (name)
);
COMMENT ON TABLE nas IS 'NAS 实例（每台 NAS 一行，OS 挂载点只读）';
COMMENT ON COLUMN nas.name IS '逻辑 NAS 编号，如 nas01';

-- 2. customer_space：客户空间 --------------------------------------
CREATE TABLE customer_space (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(128) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_customer_space_code UNIQUE (code)
);
COMMENT ON TABLE customer_space IS '客户空间；code 参与 S3 Object Key 前缀，有任务后不可变更';

-- 3. directory：被监控目录（一目录只对应一个客户空间，BR-01） ----------
CREATE TABLE directory (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nas_id             BIGINT        NOT NULL REFERENCES nas (id),
    customer_space_id  BIGINT        NOT NULL REFERENCES customer_space (id),
    code               VARCHAR(128)  NOT NULL,
    path               VARCHAR(1024) NOT NULL,
    extensions         JSONB         NOT NULL DEFAULT '[]'::jsonb,
    scan_interval_sec  BIGINT        NOT NULL,
    max_concurrency    INTEGER       NOT NULL,
    enabled            BOOLEAN       NOT NULL DEFAULT TRUE,
    last_scan_at       TIMESTAMPTZ   NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_directory_code UNIQUE (code),
    CONSTRAINT uq_directory_nas_path UNIQUE (nas_id, path),
    CONSTRAINT ck_directory_scan_interval_positive CHECK (scan_interval_sec > 0),
    CONSTRAINT ck_directory_max_concurrency_positive CHECK (max_concurrency >= 1)
);
CREATE INDEX idx_directory_enabled_nas ON directory (enabled, nas_id);
COMMENT ON COLUMN directory.extensions IS '允许的后缀数组，如 ["zip","xml"]';
COMMENT ON COLUMN directory.last_scan_at IS '最近一次成功列目录时刻（Scanner 写）';

-- 4. file：逻辑文件（同一 (nas,directory,relative_path) 唯一） ----------
CREATE TABLE file (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nas_id        BIGINT        NOT NULL REFERENCES nas (id),
    directory_id  BIGINT        NOT NULL REFERENCES directory (id),
    relative_path VARCHAR(2048) NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_file UNIQUE (nas_id, directory_id, relative_path)
);
CREATE INDEX idx_file_directory_path ON file (directory_id, relative_path);
COMMENT ON COLUMN file.nas_id IS '冗余自 directory，便于唯一约束与查询（06 §2.4）';

-- 5. file_version：文件版本（稳定语义 = stable_at 非空） --------------
CREATE TABLE file_version (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    file_id       BIGINT      NOT NULL REFERENCES file (id),
    version_no    INTEGER     NOT NULL,
    size_bytes    BIGINT      NOT NULL,
    mtime         TIMESTAMPTZ NOT NULL,
    fingerprint   VARCHAR(128) NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    stable_at     TIMESTAMPTZ NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_file_version_fingerprint UNIQUE (file_id, fingerprint),
    CONSTRAINT uq_file_version_no UNIQUE (file_id, version_no),
    CONSTRAINT ck_file_version_no_positive CHECK (version_no > 0),
    CONSTRAINT ck_file_version_size_nonnegative CHECK (size_bytes >= 0)
);
CREATE INDEX idx_file_version_stable ON file_version (file_id, stable_at);
COMMENT ON COLUMN file_version.fingerprint IS 'SHA-256(relative_path+size+mtime)，非内容 hash（BR-15）';
COMMENT ON COLUMN file_version.stable_at IS '非空 = 已稳定（连续两次观察一致）；A5 裁决移除 status 列';

-- 6. transfer_task：传输任务（9 态状态机；file_id/version_no 为 A3 冗余） -
CREATE TABLE transfer_task (
    id                        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    file_version_id           BIGINT        NOT NULL,
    file_id                   BIGINT        NOT NULL REFERENCES file (id),
    version_no                INTEGER       NOT NULL,
    directory_id              BIGINT        NOT NULL REFERENCES directory (id),
    customer_space_id         BIGINT        NOT NULL REFERENCES customer_space (id),
    file_path                 VARCHAR(2048) NOT NULL,
    file_size_bytes           BIGINT        NOT NULL,
    file_mtime                TIMESTAMPTZ   NOT NULL,
    s3_bucket                 VARCHAR(255)  NOT NULL,
    s3_object_key             VARCHAR(2048) NOT NULL,
    s3_uploaded_at            TIMESTAMPTZ   NULL,
    gateway_route             VARCHAR(255)  NOT NULL,
    config_version            VARCHAR(64)   NOT NULL,
    status                    VARCHAR(32)   NOT NULL,
    priority                  INTEGER       NOT NULL DEFAULT 0,
    retry_count               INTEGER       NOT NULL DEFAULT 0,
    next_retry_at             TIMESTAMPTZ   NULL,
    worker_id                 VARCHAR(128)  NULL,
    claimed_at                TIMESTAMPTZ   NULL,
    lease_until               TIMESTAMPTZ   NULL,
    last_attempt_finished_at  TIMESTAMPTZ   NULL,
    cancel_reason             VARCHAR(64)   NULL,
    superseded_by_version_no  INTEGER       NULL,
    cancelled_at              TIMESTAMPTZ   NULL,
    started_at                TIMESTAMPTZ   NULL,
    completed_at              TIMESTAMPTZ   NULL,
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_transfer_task_file_version UNIQUE (file_version_id),
    CONSTRAINT fk_transfer_task_file_version FOREIGN KEY (file_version_id)
        REFERENCES file_version (id),
    CONSTRAINT ck_transfer_task_status CHECK (status IN (
        'DISCOVERED', 'STABILITY_CHECK', 'READY', 'UPLOADING', 'S3_UPLOADED',
        'GATEWAY_DELIVERING', 'WAITING_RETRY', 'DELIVERED', 'CANCELLED')),
    CONSTRAINT ck_transfer_task_retry_count_nonnegative CHECK (retry_count >= 0),
    CONSTRAINT ck_transfer_task_version_no_positive CHECK (version_no > 0),
    CONSTRAINT ck_transfer_task_size_nonnegative CHECK (file_size_bytes >= 0)
);
CREATE INDEX idx_transfer_task_claim
    ON transfer_task (status, next_retry_at, created_at);
CREATE INDEX idx_transfer_task_stale_lease
    ON transfer_task (status, lease_until);
CREATE INDEX idx_transfer_task_file_version
    ON transfer_task (file_id, version_no);
CREATE INDEX idx_transfer_task_customer_status
    ON transfer_task (customer_space_id, status);
COMMENT ON COLUMN transfer_task.file_id IS 'A3 冗余：串行守卫/supersede 依赖 (file_id, version_no)';
COMMENT ON COLUMN transfer_task.status IS '唯一权威任务状态（9 态，无 FAILED/CLAIMED/SUPERSEDED）';

-- 7. transfer_attempt：执行尝试（结果未知 = outcome=UNKNOWN，A4） -------
CREATE TABLE transfer_attempt (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_task_id  BIGINT       NOT NULL REFERENCES transfer_task (id),
    attempt_type      VARCHAR(32)  NOT NULL,
    attempt_no        INTEGER      NOT NULL,
    started_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ  NULL,
    success           BOOLEAN      NOT NULL DEFAULT FALSE,
    outcome           VARCHAR(16)  NOT NULL,
    http_status       INTEGER      NULL,
    error_code        VARCHAR(128) NULL,
    error_message     TEXT         NULL,
    request_id        VARCHAR(128) NULL,
    gateway_request_id VARCHAR(128) NULL,
    duration_ms       BIGINT       NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_transfer_attempt UNIQUE (transfer_task_id, attempt_type, attempt_no),
    CONSTRAINT ck_transfer_attempt_type CHECK (attempt_type IN ('S3_UPLOAD', 'GATEWAY_DELIVER')),
    CONSTRAINT ck_transfer_attempt_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'UNKNOWN')),
    CONSTRAINT ck_transfer_attempt_no_positive CHECK (attempt_no > 0)
);
CREATE INDEX idx_transfer_attempt_task_no ON transfer_attempt (transfer_task_id, attempt_no);
CREATE INDEX idx_transfer_attempt_type_success ON transfer_attempt (attempt_type, success, created_at);
COMMENT ON COLUMN transfer_attempt.outcome IS 'A4：SUCCESS/FAILURE/UNKNOWN；UNKNOWN 表示结果未知，按失败重投';

-- 8. transfer_event（可选审计表）：状态迁移 / operator 动作留痕 ---------
CREATE TABLE transfer_event (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_task_id  BIGINT       NOT NULL REFERENCES transfer_task (id),
    from_status       VARCHAR(32)  NULL,
    to_status         VARCHAR(32)  NOT NULL,
    reason            VARCHAR(255) NULL,
    operator          VARCHAR(128) NOT NULL,
    detail            JSONB        NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_transfer_event_task ON transfer_event (transfer_task_id, created_at);
CREATE INDEX idx_transfer_event_to_status ON transfer_event (to_status, created_at);
COMMENT ON COLUMN transfer_event.operator IS 'system 或操作人（manual retry / supersede 留痕）';
