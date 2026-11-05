-- 康衡 HealthOS Java 版：核心切片表结构
-- 全部使用 IF NOT EXISTS，可重复执行（spring.sql.init.mode=always）

CREATE TABLE IF NOT EXISTS classic_document (
    id                VARCHAR(40)  PRIMARY KEY,
    title             VARCHAR(400) NOT NULL,
    source_object_key VARCHAR(400),
    content_type      VARCHAR(120),
    sha256            VARCHAR(64)  NOT NULL,
    status            VARCHAR(24)  NOT NULL,
    current_node      VARCHAR(40),
    error_code        VARCHAR(60),
    error_message     VARCHAR(1000),
    -- 上传者。流水线是异步跑的，不带登录态，靠这一列把轨迹归属还原到人
    created_by        VARCHAR(200),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 老库补列：没有上传者的历史文档统一归到 system
ALTER TABLE classic_document ADD COLUMN IF NOT EXISTS created_by VARCHAR(200);
UPDATE classic_document SET created_by = 'system' WHERE created_by IS NULL OR created_by = '';

CREATE TABLE IF NOT EXISTS classic_job (
    id                VARCHAR(40) PRIMARY KEY,
    document_id       VARCHAR(40) NOT NULL,
    status            VARCHAR(24) NOT NULL,
    attempt           INT         NOT NULL DEFAULT 0,
    provider_snapshot TEXT,
    started_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    error_code        VARCHAR(60),
    error_message     VARCHAR(1000),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_classic_job_document ON classic_job (document_id);

CREATE TABLE IF NOT EXISTS pipeline_node (
    id             VARCHAR(40)  PRIMARY KEY,
    document_id    VARCHAR(40)  NOT NULL,
    job_id         VARCHAR(40)  NOT NULL,
    code           VARCHAR(40)  NOT NULL,
    label          VARCHAR(120) NOT NULL,
    order_index    INT          NOT NULL,
    status         VARCHAR(24)  NOT NULL,
    progress       NUMERIC(5, 4) NOT NULL DEFAULT 0,
    detail         TEXT,
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,
    error_message  VARCHAR(1000),
    CONSTRAINT uk_pipeline_node UNIQUE (job_id, code)
);

CREATE TABLE IF NOT EXISTS artifact (
    id          VARCHAR(40)  PRIMARY KEY,
    document_id VARCHAR(40)  NOT NULL,
    job_id      VARCHAR(40)  NOT NULL,
    kind        VARCHAR(40)  NOT NULL,
    object_key  VARCHAR(400) NOT NULL,
    sha256      VARCHAR(64)  NOT NULL,
    size_bytes  BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_artifact_document ON artifact (document_id, kind);

CREATE TABLE IF NOT EXISTS extraction_checkpoint (
    id          VARCHAR(40)  PRIMARY KEY,
    document_id VARCHAR(40)  NOT NULL,
    job_id      VARCHAR(40)  NOT NULL,
    segment_key VARCHAR(160) NOT NULL,
    payload     TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_extraction_checkpoint UNIQUE (document_id, segment_key)
);

CREATE TABLE IF NOT EXISTS document_chunk (
    id          VARCHAR(40)  PRIMARY KEY,
    document_id VARCHAR(40)  NOT NULL,
    chunk_index INT          NOT NULL,
    char_start  INT          NOT NULL,
    char_end    INT          NOT NULL,
    page_from   INT,
    content     TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_document_chunk UNIQUE (document_id, chunk_index)
);

-- 切片向量。向量本身存 JSON 数组（已 L2 归一化），不依赖 pgvector：
-- 官方 postgres 镜像不带该扩展，而本地候选集只有几十到几百条，Java 侧算余弦足够；
-- 上 pgvector 时把 vector 换成 vector(1024) 并加 HNSW 索引即可，读写只动 ChunkVectorService。
CREATE TABLE IF NOT EXISTS chunk_vector (
    id           VARCHAR(40)  PRIMARY KEY,
    chunk_id     VARCHAR(40)  NOT NULL,
    document_id  VARCHAR(40)  NOT NULL,
    model        VARCHAR(120) NOT NULL,
    dimension    INT          NOT NULL,
    vector       TEXT         NOT NULL,
    content_hash VARCHAR(64)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_chunk_vector UNIQUE (chunk_id, model)
);

CREATE INDEX IF NOT EXISTS ix_chunk_vector_document ON chunk_vector (document_id, model);

CREATE TABLE IF NOT EXISTS graph_entity (
    id              VARCHAR(40)  PRIMARY KEY,
    document_id     VARCHAR(40)  NOT NULL,
    name            VARCHAR(200) NOT NULL,
    normalized_name VARCHAR(200) NOT NULL,
    entity_type     VARCHAR(40)  NOT NULL,
    aliases         TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_graph_entity UNIQUE (document_id, normalized_name, entity_type)
);

CREATE TABLE IF NOT EXISTS graph_relation (
    id               VARCHAR(40)  PRIMARY KEY,
    document_id      VARCHAR(40)  NOT NULL,
    source_entity_id VARCHAR(40)  NOT NULL,
    target_entity_id VARCHAR(40)  NOT NULL,
    relation_type    VARCHAR(40)  NOT NULL,
    confidence       NUMERIC(5, 4) NOT NULL DEFAULT 0,
    evidence_id      VARCHAR(40),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_graph_relation UNIQUE (document_id, source_entity_id, target_entity_id, relation_type)
);

CREATE TABLE IF NOT EXISTS evidence (
    id          VARCHAR(40)  PRIMARY KEY,
    document_id VARCHAR(40)  NOT NULL,
    artifact_id VARCHAR(40),
    quote       TEXT         NOT NULL,
    char_start  INT          NOT NULL,
    char_end    INT          NOT NULL,
    page_from   INT,
    page_to     INT,
    confidence  NUMERIC(5, 4) NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_evidence_document ON evidence (document_id);

CREATE TABLE IF NOT EXISTS qa_conversation (
    id          VARCHAR(40)  PRIMARY KEY,
    document_id VARCHAR(40)  NOT NULL,
    title       VARCHAR(400) NOT NULL,
    created_by  VARCHAR(80)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS qa_message (
    id              VARCHAR(40) PRIMARY KEY,
    conversation_id VARCHAR(40) NOT NULL,
    role            VARCHAR(16) NOT NULL,
    content         TEXT        NOT NULL,
    citations       TEXT,
    branches        TEXT,
    status          VARCHAR(32) NOT NULL DEFAULT 'OK',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 并行问答上线前建好的库没有 branches 列；schema.sql 每次启动都执行，故用 IF NOT EXISTS 补齐
ALTER TABLE qa_message ADD COLUMN IF NOT EXISTS branches TEXT;

CREATE INDEX IF NOT EXISTS idx_qa_message_conversation ON qa_message (conversation_id, created_at);

-- ============================================================
-- 问答运行记录（qa run）
--
-- 为什么把"一次问答"从一次同步请求里拆出来：原来 /qa/ask 是一个同步调用，
-- 模型超时、进程重启、客户端断线都会让这次问答前功尽弃，且无法断点续跑。
-- 这里把一次问答建模成"运行（run）"，对齐原项目 qa_runs.py：
--
--   qa_run        一次问答的运行主体。status 是唯一真相来源，
--                 config_snapshot 冻结提交那一刻的模型/检索参数，
--                 context_snapshot 冻结提交那一刻的证据上下文——续跑时用冻结值，
--                 不用"现在的"上下文，否则续跑出来的答案和首次不是一回事。
--   qa_run_node   运行内部的节点（检索 / 各分支 / 汇总）。节点级状态是
--                 断点续跑的抓手：已 succeeded 的节点重跑时直接跳过。
--   qa_event      运行过程的事件流，seq 单调递增。SSE 端点按 seq 增量推送，
--                 断线后带 after_seq 重连即可补齐，不丢事件。
--   qa_attempt    节点的每一次尝试。换节点重试、进程崩溃后续跑都靠它留痕。
-- ============================================================

CREATE TABLE IF NOT EXISTS qa_run (
    id              VARCHAR(40)  PRIMARY KEY,
    conversation_id VARCHAR(40)  NOT NULL,
    document_id     VARCHAR(40)  NOT NULL,
    owner           VARCHAR(200) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    input_hash      VARCHAR(64)  NOT NULL,
    question        TEXT         NOT NULL,
    mode            VARCHAR(16)  NOT NULL DEFAULT 'parallel',
    patient_id      VARCHAR(40),
    status          VARCHAR(24)  NOT NULL DEFAULT 'QUEUED',
    version         INT          NOT NULL DEFAULT 1,
    event_seq       INT          NOT NULL DEFAULT 0,
    answer          TEXT,
    error_code      VARCHAR(64),
    error_message   TEXT,
    config_snapshot TEXT,
    context_snapshot TEXT,
    deadline_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 幂等键在同一归属人下唯一。会话是可选的（不传就新建），
    -- 所以唯一性不能建在会话上，否则不带会话的重复提交会绕过幂等。
    CONSTRAINT uk_qa_run_idem UNIQUE (owner, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_qa_run_owner ON qa_run (owner);
CREATE INDEX IF NOT EXISTS idx_qa_run_conversation ON qa_run (conversation_id, created_at DESC);

CREATE TABLE IF NOT EXISTS qa_run_node (
    id                 VARCHAR(40) PRIMARY KEY,
    run_id             VARCHAR(40) NOT NULL,
    node_key           VARCHAR(40) NOT NULL,
    label              VARCHAR(80),
    order_index        INT         NOT NULL DEFAULT 0,
    status             VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt_count      INT         NOT NULL DEFAULT 0,
    auto_attempt_limit INT         NOT NULL DEFAULT 2,
    result             TEXT,
    error_code         VARCHAR(64),
    error_message      TEXT,
    started_at         TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_qa_run_node UNIQUE (run_id, node_key)
);

CREATE INDEX IF NOT EXISTS idx_qa_run_node_run ON qa_run_node (run_id, order_index);

CREATE TABLE IF NOT EXISTS qa_event (
    id         VARCHAR(40) PRIMARY KEY,
    run_id     VARCHAR(40) NOT NULL,
    seq        INT         NOT NULL,
    type       VARCHAR(64) NOT NULL,
    payload    TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_qa_event_seq UNIQUE (run_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_qa_event_run ON qa_event (run_id, seq);

CREATE TABLE IF NOT EXISTS qa_attempt (
    id           VARCHAR(40) PRIMARY KEY,
    run_id       VARCHAR(40) NOT NULL,
    node_key     VARCHAR(40) NOT NULL,
    attempt_no   INT         NOT NULL,
    status       VARCHAR(24) NOT NULL DEFAULT 'RUNNING',
    input_hash   VARCHAR(64),
    detail       TEXT,
    error_code   VARCHAR(64),
    error_message TEXT,
    started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_qa_attempt UNIQUE (run_id, node_key, attempt_no)
);

CREATE INDEX IF NOT EXISTS idx_qa_attempt_run ON qa_attempt (run_id, node_key);

CREATE TABLE IF NOT EXISTS patient (
    id         VARCHAR(40)  PRIMARY KEY,
    name       VARCHAR(80)  NOT NULL,
    gender     VARCHAR(16)  NOT NULL,
    birth_date DATE,
    phone      VARCHAR(40),
    risk_level VARCHAR(24)  NOT NULL DEFAULT 'UNKNOWN',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS patient_metric (
    id             VARCHAR(40)   PRIMARY KEY,
    patient_id     VARCHAR(40)   NOT NULL,
    owner          VARCHAR(200),
    code           VARCHAR(60)   NOT NULL,
    name           VARCHAR(120)  NOT NULL,
    value          NUMERIC(18, 4) NOT NULL,
    unit           VARCHAR(24),
    reference_low  NUMERIC(18, 4),
    reference_high NUMERIC(18, 4),
    status         VARCHAR(16)   NOT NULL,
    measured_at    DATE,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_patient_metric_patient ON patient_metric (patient_id);

CREATE TABLE IF NOT EXISTS outbox_event (
    id             VARCHAR(40)  PRIMARY KEY,
    aggregate_type VARCHAR(60)  NOT NULL,
    aggregate_id   VARCHAR(40)  NOT NULL,
    event_type     VARCHAR(80)  NOT NULL,
    payload        TEXT,
    status         VARCHAR(24)  NOT NULL,
    attempts       INT          NOT NULL DEFAULT 0,
    available_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error     VARCHAR(1000),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox_event (status, available_at);

CREATE TABLE IF NOT EXISTS idempotency_record (
    idem_key    VARCHAR(200) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    resource_id  VARCHAR(60) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 外部知识库（RAGFlow）索引登记：重建时先按这里记录的 datasetId 删旧库
CREATE TABLE IF NOT EXISTS knowledge_index (
    document_id     VARCHAR(40) PRIMARY KEY,
    provider        VARCHAR(40) NOT NULL,
    dataset_id      VARCHAR(80),
    external_doc_id VARCHAR(80),
    chunk_count     INT         NOT NULL DEFAULT 0,
    status          VARCHAR(24) NOT NULL DEFAULT 'OK',
    detail          VARCHAR(1000),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================
-- 观测（tracing）
-- 三张表分工：trace_run 是"一次业务执行的收据"，trace_span 是"这次执行里
-- 每个操作的耗时与结果"，trace_event 是"给不出 span 的零散事实"（保留清理、
-- 状态流转等）。三者都只存观测数据，不参与任何业务判定，可随时整表清空。
-- ============================================================

CREATE TABLE IF NOT EXISTS trace_run (
    trace_id            VARCHAR(32)  PRIMARY KEY,
    root_span_id        VARCHAR(16)  NOT NULL,
    business_type       VARCHAR(40)  NOT NULL,
    business_id         VARCHAR(64)  NOT NULL,
    execution_no        INT          NOT NULL DEFAULT 1,
    owner               VARCHAR(80),
    patient_id          VARCHAR(64),
    previous_trace_id   VARCHAR(32),
    status              VARCHAR(24)  NOT NULL DEFAULT 'RUNNING',
    outcome             VARCHAR(24),
    error_code          VARCHAR(60),
    error_message       VARCHAR(1000),
    accepted_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,
    expires_at          TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_trace_run_business ON trace_run (business_type, business_id, execution_no DESC);
CREATE INDEX IF NOT EXISTS idx_trace_run_owner ON trace_run (owner, accepted_at DESC);
CREATE INDEX IF NOT EXISTS idx_trace_run_expires ON trace_run (expires_at);

-- 老库补列：outcome 是后加的（区分"健康度"与"业务结局"两套词表）。
ALTER TABLE trace_run ADD COLUMN IF NOT EXISTS outcome VARCHAR(24);

-- 老行回填。加 outcome 之前，业务状态词是直接写进 status 的（问答写 OK、
-- 流水线写 SUCCEEDED），同列两套词表，按状态聚合时会被拆成两半。
-- 先收失败同义词，再兜底收成 SUCCEEDED；条件写全所以反复执行也是幂等的。
UPDATE trace_run SET outcome = status, status = 'FAILED'
 WHERE status IN ('FAIL', 'ERROR');
UPDATE trace_run SET outcome = status, status = 'INTERRUPTED'
 WHERE status IN ('CANCELLED', 'CANCELED', 'ABORTED');
UPDATE trace_run SET outcome = status, status = 'SUCCEEDED'
 WHERE status NOT IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'INTERRUPTED');

CREATE TABLE IF NOT EXISTS trace_span (
    id             BIGSERIAL    PRIMARY KEY,
    trace_id       VARCHAR(32)  NOT NULL,
    span_id        VARCHAR(16)  NOT NULL,
    parent_span_id VARCHAR(16),
    name           VARCHAR(160) NOT NULL,
    kind           VARCHAR(40)  NOT NULL,
    service        VARCHAR(80)  NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'OK',
    error_code     VARCHAR(60),
    error_message  VARCHAR(1000),
    attributes     TEXT,
    started_at     TIMESTAMPTZ  NOT NULL,
    completed_at   TIMESTAMPTZ,
    duration_ms    BIGINT,
    CONSTRAINT uk_trace_span UNIQUE (trace_id, span_id)
);

CREATE INDEX IF NOT EXISTS idx_trace_span_trace ON trace_span (trace_id, started_at);
CREATE INDEX IF NOT EXISTS idx_trace_span_started ON trace_span (started_at);

CREATE TABLE IF NOT EXISTS trace_event (
    id         BIGSERIAL    PRIMARY KEY,
    trace_id   VARCHAR(32)  NOT NULL,
    span_id    VARCHAR(16),
    name       VARCHAR(120) NOT NULL,
    detail     TEXT,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_trace_event_trace ON trace_event (trace_id, created_at);

CREATE TABLE IF NOT EXISTS trace_maintenance_state (
    id            VARCHAR(40)  PRIMARY KEY,
    last_run_at   TIMESTAMPTZ,
    next_run_at   TIMESTAMPTZ,
    last_result   TEXT,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ============================================================
-- 患者域：生命周期 / 修订 / 对象 / 批次导入 / 删除台账
-- ============================================================

ALTER TABLE patient ADD COLUMN IF NOT EXISTS status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE patient ADD COLUMN IF NOT EXISTS revision    INT         NOT NULL DEFAULT 0;
ALTER TABLE patient ADD COLUMN IF NOT EXISTS external_ref VARCHAR(80);
ALTER TABLE patient ADD COLUMN IF NOT EXISTS created_by  VARCHAR(200);
ALTER TABLE patient ADD COLUMN IF NOT EXISTS deleted_at  TIMESTAMPTZ;
ALTER TABLE patient ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;

-- 没有归属人的历史行统一归到 system，避免出现"谁都能读"的空 owner。
-- 必须排在下面的唯一索引之前：回填后多个空 owner 会变成同一个 'system'，
-- 索引若已建好就会撞唯一约束。
UPDATE patient SET created_by = 'system' WHERE created_by IS NULL OR created_by = '';

CREATE INDEX IF NOT EXISTS idx_patient_status ON patient (status, created_at DESC);
-- 外部业务号在同一创建者内唯一，批次重跑才不会建出重复患者
CREATE UNIQUE INDEX IF NOT EXISTS uk_patient_external_ref
    ON patient (created_by, external_ref) WHERE external_ref IS NOT NULL;

CREATE TABLE IF NOT EXISTS patient_revision (
    id         VARCHAR(40)  PRIMARY KEY,
    patient_id VARCHAR(40)  NOT NULL,
    owner      VARCHAR(200),
    revision   INT          NOT NULL,
    actor      VARCHAR(80)  NOT NULL,
    reason     VARCHAR(200) NOT NULL,
    snapshot   TEXT         NOT NULL,
    sha256     VARCHAR(64)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_patient_revision UNIQUE (patient_id, revision)
);

CREATE INDEX IF NOT EXISTS idx_patient_revision_patient ON patient_revision (patient_id, revision DESC);

CREATE TABLE IF NOT EXISTS patient_object (
    id           VARCHAR(40)  PRIMARY KEY,
    patient_id   VARCHAR(40)  NOT NULL,
    owner        VARCHAR(200),
    kind         VARCHAR(24)  NOT NULL DEFAULT 'OTHER',
    object_key   VARCHAR(400) NOT NULL,
    filename     VARCHAR(300) NOT NULL,
    content_type VARCHAR(120),
    size_bytes   BIGINT       NOT NULL,
    sha256       VARCHAR(64)  NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at   TIMESTAMPTZ,
    CONSTRAINT uk_patient_object UNIQUE (object_key)
);

CREATE INDEX IF NOT EXISTS idx_patient_object_patient ON patient_object (patient_id, status);

CREATE TABLE IF NOT EXISTS patient_batch (
    id             VARCHAR(40)  PRIMARY KEY,
    filename       VARCHAR(300) NOT NULL,
    sha256         VARCHAR(64)  NOT NULL,
    format         VARCHAR(16)  NOT NULL,
    total_rows     INT          NOT NULL DEFAULT 0,
    succeeded_rows INT          NOT NULL DEFAULT 0,
    failed_rows    INT          NOT NULL DEFAULT 0,
    status         VARCHAR(24)  NOT NULL,
    -- created_by 同时是批次归属人（owner）：批次与它建出来的患者都属于同一个人
    created_by     VARCHAR(200) NOT NULL,
    error_message  VARCHAR(1000),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_patient_batch_sha ON patient_batch (sha256);

CREATE TABLE IF NOT EXISTS patient_batch_row (
    id            VARCHAR(40)  PRIMARY KEY,
    batch_id      VARCHAR(40)  NOT NULL,
    owner         VARCHAR(200),
    row_no        INT          NOT NULL,
    external_ref  VARCHAR(80),
    payload       TEXT         NOT NULL,
    status        VARCHAR(24)  NOT NULL,
    patient_id    VARCHAR(40),
    error_code    VARCHAR(60),
    error_message VARCHAR(500),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_patient_batch_row UNIQUE (batch_id, row_no)
);

CREATE INDEX IF NOT EXISTS idx_patient_batch_row_status ON patient_batch_row (batch_id, status);

CREATE TABLE IF NOT EXISTS patient_deletion_task (
    id             VARCHAR(40)  PRIMARY KEY,
    patient_id     VARCHAR(40)  NOT NULL,
    owner          VARCHAR(200),
    status         VARCHAR(24)  NOT NULL,
    requested_by   VARCHAR(80)  NOT NULL,
    reason         VARCHAR(200) NOT NULL,
    preview_token  VARCHAR(64)  NOT NULL,
    resource_count INT          NOT NULL DEFAULT 0,
    ledger_key     VARCHAR(400),
    ledger_sha256  VARCHAR(64),
    error_message  VARCHAR(1000),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    confirmed_at   TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_patient_deletion_patient ON patient_deletion_task (patient_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_patient_deletion_status ON patient_deletion_task (status);

CREATE TABLE IF NOT EXISTS patient_deletion_resource (
    id            VARCHAR(40)  PRIMARY KEY,
    task_id       VARCHAR(40)  NOT NULL,
    owner         VARCHAR(200),
    resource_kind VARCHAR(40)  NOT NULL,
    resource_key  VARCHAR(200) NOT NULL,
    scope         VARCHAR(120),
    erased        BOOLEAN      NOT NULL DEFAULT FALSE,
    erased_at     TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_patient_deletion_resource UNIQUE (task_id, resource_kind, resource_key)
);

CREATE INDEX IF NOT EXISTS idx_patient_deletion_resource_task ON patient_deletion_resource (task_id);

-- ============================================================
-- 资料录入（patient intake）
--
-- 原始体检/病历资料 → OCR 归一化 → 分片抽取 → 两道人工核查 → 生成正式档案。
-- 这是原工程 patient_intake_* 那套的核心：AI 只负责"抄"，签字的是人。
-- ============================================================
CREATE TABLE IF NOT EXISTS patient_intake_exam (
    id                   VARCHAR(40)  PRIMARY KEY,
    owner                VARCHAR(200),
    patient_id           VARCHAR(40),
    source_filename      VARCHAR(300) NOT NULL,
    source_object_key    VARCHAR(400) NOT NULL,
    source_sha256        VARCHAR(64)   NOT NULL,
    source_size          BIGINT        NOT NULL DEFAULT 0,
    media_type           VARCHAR(120),
    -- UPLOADED / EXTRACTING / EXTRACTED / CONFIRMED / FAILED
    status               VARCHAR(24)   NOT NULL DEFAULT 'UPLOADED',
    -- 基础信息核对：PENDING / CONFIRMED / REJECTED
    basic_review_status  VARCHAR(24)   NOT NULL DEFAULT 'PENDING',
    -- 临床内容核对：PENDING / CONFIRMED / REJECTED
    clinical_review_status VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    -- 核对意见：驳回时必填，说明哪里不对
    basic_review_note    VARCHAR(1000),
    clinical_review_note VARCHAR(1000),
    markdown             TEXT,
    markdown_sha256      VARCHAR(64),
    -- OCR 元数据：引擎、页数、是否扫描件
    ocr_metadata         TEXT,
    -- 抽取器名（rule-based / llm:xxx），便于回溯"这份档案是哪套规则抄出来的"
    extractor            VARCHAR(60),
    -- 抽取汇总：字段值 + 指标列表
    aggregate            TEXT,
    extracted_abnormal_codes TEXT,
    revision             INT           NOT NULL DEFAULT 0,
    job_id               VARCHAR(40),
    error_code           VARCHAR(60),
    error_message        VARCHAR(1000),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_patient_intake_source UNIQUE (owner, source_sha256)
);

CREATE INDEX IF NOT EXISTS idx_patient_intake_owner ON patient_intake_exam (owner, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_patient_intake_status ON patient_intake_exam (status);

-- 分片：一份资料拆成若干"段"，每段单独抽取，坏段不连坐
CREATE TABLE IF NOT EXISTS patient_intake_part (
    id            VARCHAR(40)  PRIMARY KEY,
    exam_id       VARCHAR(40)  NOT NULL,
    owner         VARCHAR(200),
    part_key      VARCHAR(60)  NOT NULL,
    content       TEXT,
    -- PENDING / SUCCEEDED / FAILED / SKIPPED
    status        VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    payload       TEXT,
    error_code    VARCHAR(60),
    error_message VARCHAR(500),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_patient_intake_part UNIQUE (exam_id, part_key)
);

CREATE INDEX IF NOT EXISTS idx_patient_intake_part_exam ON patient_intake_part (exam_id, status);

-- 老库补列与回填（owner 补列必须排在下面建索引之前）
ALTER TABLE patient_intake_exam ADD COLUMN IF NOT EXISTS owner VARCHAR(200);
ALTER TABLE patient_intake_part ADD COLUMN IF NOT EXISTS owner VARCHAR(200);
UPDATE patient_intake_exam SET owner = 'system' WHERE owner IS NULL OR owner = '';
UPDATE patient_intake_part  SET owner = 'system' WHERE owner IS NULL OR owner = '';
CREATE INDEX IF NOT EXISTS idx_patient_intake_part_owner ON patient_intake_part (owner, exam_id);

-- ============================================================
-- owner 隔离：老库补列与回填
--
-- 患者域的每一张子表都带 owner，查询时必须带上它。patient.created_by 与
-- patient_batch.created_by 本来就是归属人，直接当 owner 用，不另起一列。
--
-- 回填方向是"子表 → 父表"：子表的 owner 从它爹那继承，父表没有归属人时
-- 退化成 'system'（表示系统生成、任何用户都读不到）。
--
-- 这几条 UPDATE 每次启动都会跑。行数不大时无感；真到了百万级，
-- 应该改成"只补新增行"（比如按 id 游标增量补），而不是每次全表扫。
-- ============================================================
ALTER TABLE patient_metric           ADD COLUMN IF NOT EXISTS owner VARCHAR(200);
ALTER TABLE patient_revision         ADD COLUMN IF NOT EXISTS owner VARCHAR(200);
ALTER TABLE patient_object           ADD COLUMN IF NOT EXISTS owner VARCHAR(200);
ALTER TABLE patient_batch_row        ADD COLUMN IF NOT EXISTS owner VARCHAR(200);
ALTER TABLE patient_deletion_task    ADD COLUMN IF NOT EXISTS owner VARCHAR(200);
ALTER TABLE patient_deletion_resource ADD COLUMN IF NOT EXISTS owner VARCHAR(200);

-- 父表兜底：没有归属人的历史行统一归到 system，避免出现"谁都能读"的空 owner
-- （patient 的兜底已放在 uk_patient_external_ref 之前做过一次）
UPDATE patient_batch SET created_by = 'system' WHERE created_by IS NULL OR created_by = '';

-- 子表回填
UPDATE patient_metric m SET owner = p.created_by FROM patient p
    WHERE p.id = m.patient_id AND (m.owner IS NULL OR m.owner = '');
UPDATE patient_revision r SET owner = p.created_by FROM patient p
    WHERE p.id = r.patient_id AND (r.owner IS NULL OR r.owner = '');
UPDATE patient_object o SET owner = p.created_by FROM patient p
    WHERE p.id = o.patient_id AND (o.owner IS NULL OR o.owner = '');
UPDATE patient_batch_row br SET owner = b.created_by FROM patient_batch b
    WHERE b.id = br.batch_id AND (br.owner IS NULL OR br.owner = '');
UPDATE patient_deletion_task t SET owner = p.created_by FROM patient p
    WHERE p.id = t.patient_id AND (t.owner IS NULL OR t.owner = '');
UPDATE patient_deletion_resource r SET owner = t.owner FROM patient_deletion_task t
    WHERE t.id = r.task_id AND (r.owner IS NULL OR r.owner = '');

-- 索引统一放在补列与回填之后：老库里 owner 列是 ALTER 加的，
-- 先建索引会报 "column owner does not exist"。
CREATE INDEX IF NOT EXISTS idx_patient_metric_owner ON patient_metric (owner, patient_id);
CREATE INDEX IF NOT EXISTS idx_patient_revision_owner ON patient_revision (owner, patient_id);
CREATE INDEX IF NOT EXISTS idx_patient_object_owner ON patient_object (owner, patient_id);
CREATE INDEX IF NOT EXISTS idx_patient_batch_owner ON patient_batch (created_by, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_patient_batch_row_owner ON patient_batch_row (owner, batch_id);
CREATE INDEX IF NOT EXISTS idx_patient_deletion_owner ON patient_deletion_task (owner, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_patient_deletion_resource_owner ON patient_deletion_resource (owner, task_id);


-- ============================================================
-- 健康管理（care）
--
-- 报告（report）与四周计划（plan）共用一张 care_document，用 kind 区分。
--
-- 核心语义（对齐原工程 care_documents / care_signatures / care_checkins）：
--   1. 报告只能从"已确认的体检"生成，且生成时把体检内容冻结成 source_snapshot；
--      之后体检改了不影响已生成报告（可追溯"这份报告当时依据什么"）；
--   2. 状态机：DRAFT（草稿，可编辑）→ SIGNED（已签发，不可再编辑）
--      → 修订走 revise 生成新草稿，parent_document_id 指回上一版；
--   3. 签发是"登录医生身份确认"，不是硬件 CA 电子签名——这一点必须写进
--      对外文案的免责声明里，别让人误以为法律效力等价；
--   4. 计划确认（ACTIVE）后才能打卡，打卡带版本号乐观锁。
-- ============================================================
CREATE TABLE IF NOT EXISTS care_document (
    id                   VARCHAR(40)  PRIMARY KEY,
    owner                VARCHAR(200) NOT NULL,
    -- report（健康评估报告）/ plan（四周执行计划）
    kind                 VARCHAR(20)   NOT NULL,
    patient_id           VARCHAR(40)  NOT NULL,
    -- 来源体检与体检修订号：报告的"依据"
    source_exam_id       VARCHAR(40),
    source_exam_revision INT,
    -- 来源报告与报告修订号：计划的"依据"、修订草稿的"修订来源"
    source_report_id     VARCHAR(40),
    source_report_revision INT,
    -- 发布基线：草稿起草时当前生效的同 kind 文档。发布时基线已变就拒绝，
    -- 避免患者同时有两份生效报告/计划。必须在入库时一起写入。
    parent_document_id   VARCHAR(40),
    -- 幂等键：同一归属人同一 kind 下的唯一键，避免重复提交生成两份
    idempotency_key      VARCHAR(200) NOT NULL,
    -- 输入指纹：同一幂等键但输入变了要报错，不能静默复用
    input_fingerprint    VARCHAR(64),
    revision             INT           NOT NULL DEFAULT 1,
    -- DRAFT / SIGNED / ACTIVE / SUPERSEDED
    status               VARCHAR(24)   NOT NULL DEFAULT 'DRAFT',
    -- 分节内容 JSON：{sectionKey: {text, metricRefs}}
    sections             TEXT,
    -- 汇总 JSON：完整度、任务清单、打卡进度
    aggregate            TEXT,
    -- 来源体检的不可变快照
    source_snapshot      TEXT,
    -- 计划起始日期
    start_date           VARCHAR(10),
    -- 签发的修订号
    signed_revision      INT,
    -- 当前生效的修订号
    active_revision      INT,
    job_id               VARCHAR(40),
    error_code           VARCHAR(60),
    error_message        VARCHAR(1000),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_care_document_idem UNIQUE (owner, kind, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_care_document_owner ON care_document (owner, kind, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_care_document_patient ON care_document (patient_id, kind, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_care_document_status ON care_document (status);

-- 修订快照：每次改稿留一份当时的完整内容，可回溯可对比
CREATE TABLE IF NOT EXISTS care_revision (
    id            VARCHAR(40)  PRIMARY KEY,
    document_id   VARCHAR(40)  NOT NULL,
    revision      INT           NOT NULL,
    snapshot      TEXT,
    actor         VARCHAR(200) NOT NULL,
    reason        VARCHAR(1000),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_care_revision UNIQUE (document_id, revision)
);

CREATE INDEX IF NOT EXISTS idx_care_revision_document ON care_revision (document_id, revision DESC);

-- 审计流水：谁在什么时候改了什么
CREATE TABLE IF NOT EXISTS care_audit (
    id             VARCHAR(40)  PRIMARY KEY,
    document_id    VARCHAR(40)  NOT NULL,
    revision       INT           NOT NULL,
    actor          VARCHAR(200) NOT NULL,
    action         VARCHAR(80)  NOT NULL,
    reason         VARCHAR(1000),
    changed_section VARCHAR(50),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_care_audit_document ON care_audit (document_id, created_at DESC);

-- 签发记录：一份文档只能签一次，重复签发按内容比对
CREATE TABLE IF NOT EXISTS care_signature (
    id               VARCHAR(40)  PRIMARY KEY,
    document_id      VARCHAR(40)  NOT NULL,
    owner            VARCHAR(200) NOT NULL,
    signed_revision  INT           NOT NULL,
    expected_revision INT          NOT NULL,
    signer           VARCHAR(200) NOT NULL,
    review_comment   VARCHAR(2000) NOT NULL,
    content_hash     VARCHAR(64)  NOT NULL,
    start_date       VARCHAR(10),
    -- 签发报告时同步生成的计划文档
    plan_document_id VARCHAR(40),
    kind             VARCHAR(40)  NOT NULL DEFAULT 'authenticated_identity_confirmation',
    signed_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_care_signature UNIQUE (document_id)
);

CREATE INDEX IF NOT EXISTS idx_care_signature_owner ON care_signature (owner, signed_at DESC);

-- 患者当前生效状态：一个患者同时只有一个生效报告和一个生效计划
CREATE TABLE IF NOT EXISTS care_patient_state (
    patient_id            VARCHAR(40)  PRIMARY KEY,
    owner                 VARCHAR(200) NOT NULL,
    active_report_id      VARCHAR(40),
    active_report_revision INT,
    active_plan_id        VARCHAR(40),
    active_plan_revision  INT,
    version               INT           NOT NULL DEFAULT 1,
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_care_patient_state_owner ON care_patient_state (owner);

-- 发布基线回填（幂等）：早期版本在 insert 之后才给 parent_document_id 赋值，
-- 库里存的是 null，于是发布守卫永远判定"基线已变"，任何草稿都签不出去。
-- 这里按当前生效的同 kind 文档补齐，只补 null，不会覆盖已经记好的基线。
UPDATE care_document d
   SET parent_document_id = s.active_report_id
  FROM care_patient_state s
 WHERE d.patient_id = s.patient_id
   AND d.kind = 'report'
   AND d.status = 'DRAFT'
   AND d.parent_document_id IS NULL;

UPDATE care_document d
   SET parent_document_id = s.active_plan_id
  FROM care_patient_state s
 WHERE d.patient_id = s.patient_id
   AND d.kind = 'plan'
   AND d.status = 'DRAFT'
   AND d.parent_document_id IS NULL;

-- 打卡：计划里每个任务一条，version 做乐观锁
CREATE TABLE IF NOT EXISTS care_checkin (
    id           VARCHAR(40)  PRIMARY KEY,
    document_id  VARCHAR(40)  NOT NULL,
    owner        VARCHAR(200) NOT NULL,
    task_id      VARCHAR(100) NOT NULL,
    completed    BOOLEAN      NOT NULL DEFAULT FALSE,
    version      INT          NOT NULL DEFAULT 1,
    actor        VARCHAR(200) NOT NULL,
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uk_care_checkin UNIQUE (document_id, task_id)
);

CREATE INDEX IF NOT EXISTS idx_care_checkin_document ON care_checkin (document_id);
CREATE INDEX IF NOT EXISTS idx_care_checkin_owner ON care_checkin (owner, document_id);

-- 患者知识库绑定：把该患者问答可以用到的外部知识库（RAGFlow dataset）挂到患者身上。
--
-- 为什么绑定而不是"全局一个库"：不同患者的资料彼此不该串味。原工程用
-- patient → dataset 绑定实现这一点；本工程沿用它，但只存映射，不复制数据。
--
-- status 只有两个值：
--   ACTIVE —— 最近一次核对时 dataset 仍存在，参与检索；
--   STALE  —— 对账发现库已删除/不可访问，保留记录但不参与检索（不静默删除，
--             否则运维看不到"这个患者的库什么时候没的"）。
CREATE TABLE IF NOT EXISTS qa_knowledge_binding (
    id              VARCHAR(40)  PRIMARY KEY,
    owner           VARCHAR(200) NOT NULL,
    patient_id      VARCHAR(40)  NOT NULL,
    dataset_id      VARCHAR(120) NOT NULL,
    dataset_name    VARCHAR(400),
    note            VARCHAR(400),
    status          VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    last_checked_at TIMESTAMPTZ,
    last_error      VARCHAR(600),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 同一个人对同一患者重复绑同一个库没有意义，交给数据库兜底
CREATE UNIQUE INDEX IF NOT EXISTS ux_qa_kb_binding
    ON qa_knowledge_binding (owner, patient_id, dataset_id);

CREATE INDEX IF NOT EXISTS idx_qa_kb_binding_patient
    ON qa_knowledge_binding (owner, patient_id, status);

-- ============================================================
-- 患者长效记忆（longitudinal memory）与随访
--
-- 对应原工程 memory_* 一族。这里保留它最关键的语义，砍掉跨进程调度层：
--   ① 版本**不可变**：刷新只新增版本并移动指针，绝不改写历史；
--   ② 事实只从**冻结来源**取（已落库的修订快照 / 已签发的照护修订 / 随访原文），
--      sources 逐条记 content_hash，导出 Markdown 带 X-Content-SHA256 可校验；
--   ③ 随访用 operation_id 幂等，expected_memory_id 做乐观并发控制。
--
-- 不复刻原工程的租约 / fencing token / 心跳 / attempt 表 / Agent 摘录：
-- 本工程是单库单实例，写操作统一在 patient 行上加 FOR UPDATE 串行，
-- 代价写进 README §9 差异清单，不假装等价。
-- ============================================================

-- 不可变版本链。data/sources 是采样当时的 JSON 快照，markdown 是渲染结果；
-- content_hash = sha256(markdown)，source_hash = sha256(去掉采样时间的来源清单)。
CREATE TABLE IF NOT EXISTS patient_memory_version (
    id               VARCHAR(40)  PRIMARY KEY,
    owner            VARCHAR(200) NOT NULL,
    patient_id       VARCHAR(40)  NOT NULL,
    version          INT          NOT NULL,
    schema_version   VARCHAR(50)  NOT NULL,
    generation_mode  VARCHAR(50)  NOT NULL,
    content_hash     VARCHAR(64)  NOT NULL,
    source_hash      VARCHAR(64)  NOT NULL,
    data             TEXT         NOT NULL,
    sources          TEXT         NOT NULL,
    markdown         TEXT         NOT NULL,
    sampled_at       TIMESTAMPTZ  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 当前发布指针。与版本链分开：一个是历史，一个是"现在看哪一版"。
CREATE TABLE IF NOT EXISTS patient_memory_state (
    patient_id         VARCHAR(40)  PRIMARY KEY,
    owner              VARCHAR(200) NOT NULL,
    current_memory_id  VARCHAR(40)  NOT NULL,
    version            INT          NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 随访原始条目。entries 保留用户原文（分类 + 文本），不做任何改写或摘要。
CREATE TABLE IF NOT EXISTS patient_followup (
    id                VARCHAR(40)  PRIMARY KEY,
    owner             VARCHAR(200) NOT NULL,
    patient_id        VARCHAR(40)  NOT NULL,
    operation_id      VARCHAR(64)  NOT NULL,
    request_sequence  INT          NOT NULL,
    occurred_at       TIMESTAMPTZ  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor             VARCHAR(200) NOT NULL,
    origin            VARCHAR(40)  NOT NULL,
    entries           TEXT         NOT NULL
);

-- 异步发布任务。同一 patient 的 sequence 由 patient 行锁串行分配。
CREATE TABLE IF NOT EXISTS patient_memory_job (
    id                  VARCHAR(40)  PRIMARY KEY,
    owner               VARCHAR(200) NOT NULL,
    patient_id          VARCHAR(40)  NOT NULL,
    operation_id        VARCHAR(64)  NOT NULL,
    request_sequence    INT          NOT NULL,
    request_hash        VARCHAR(64)  NOT NULL,
    kind                VARCHAR(40)  NOT NULL,
    feedback_id         VARCHAR(40),
    expected_memory_id  VARCHAR(40),
    status              VARCHAR(24)  NOT NULL DEFAULT 'QUEUED',
    attempt_count       INT          NOT NULL DEFAULT 0,
    result_memory_id    VARCHAR(40),
    error               VARCHAR(1000),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_pmem_version
    ON patient_memory_version (patient_id, version);
CREATE INDEX IF NOT EXISTS idx_pmem_version_owner
    ON patient_memory_version (owner, patient_id, version DESC);

CREATE UNIQUE INDEX IF NOT EXISTS ux_pmem_followup_op
    ON patient_followup (patient_id, operation_id);
CREATE INDEX IF NOT EXISTS idx_pmem_followup_seq
    ON patient_followup (owner, patient_id, request_sequence DESC);

CREATE UNIQUE INDEX IF NOT EXISTS ux_pmem_job_op
    ON patient_memory_job (patient_id, operation_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_pmem_job_seq
    ON patient_memory_job (patient_id, request_sequence);
CREATE INDEX IF NOT EXISTS idx_pmem_job_owner
    ON patient_memory_job (owner, patient_id, status);

-- ============================================================================
-- 模型配置中心（model routing）
--
-- 一条业务主线（PATIENT_RECORD / MEDICAL_CLASSIC / REPORT / HEALTH_PLAN /
-- PATIENT_QA / PATIENT_MEMORY / MCP_AGENT）一条记录，各自主管自己的模型、
-- 参数与密钥。所有改动都是"追加一个版本 + 移动当前版本号"，
-- 因此"上次改的是什么、谁改的、为什么改"永远查得到。
-- ============================================================================

-- 当前版本指针：只记当前是第几版，真正的配置在 model_route_versions。
CREATE TABLE IF NOT EXISTS model_routes (
    pipeline_key   VARCHAR(40) PRIMARY KEY,
    version        INT         NOT NULL DEFAULT 1,
    last_probe_at  TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 加密后的模型密钥。同一条主线可以有多版密钥，版本号与配置版本一一对应。
CREATE TABLE IF NOT EXISTS model_credentials (
    id            VARCHAR(40) PRIMARY KEY,
    pipeline_key  VARCHAR(40) NOT NULL,
    version       INT         NOT NULL,
    ciphertext    TEXT        NOT NULL,
    key_id        VARCHAR(32) NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_mcred_key_version
    ON model_credentials (pipeline_key, version);

-- 不可变配置版本。config 是 JSON 文本（provider/model/protocol/parameters...）。
CREATE TABLE IF NOT EXISTS model_route_versions (
    id                 VARCHAR(40) PRIMARY KEY,
    pipeline_key       VARCHAR(40) NOT NULL,
    version            INT         NOT NULL,
    config             TEXT        NOT NULL,
    credential_ref     VARCHAR(40),
    credential_version INT,
    actor              VARCHAR(200) NOT NULL,
    change_reason      VARCHAR(500) NOT NULL DEFAULT '',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_mroute_key_version
    ON model_route_versions (pipeline_key, version);

-- 能力探测结果。fingerprint = HMAC(主密钥, 配置+密钥)，
-- 用来判断"当前配置是否已经被探测过"——密钥换了就是另一把配置，必须重探。
CREATE TABLE IF NOT EXISTS model_route_probes (
    id             VARCHAR(40) PRIMARY KEY,
    pipeline_key   VARCHAR(40) NOT NULL,
    config_version INT         NOT NULL,
    fingerprint    VARCHAR(64) NOT NULL,
    result         TEXT        NOT NULL,
    actor          VARCHAR(200) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_mprobe_key_fp
    ON model_route_probes (pipeline_key, fingerprint, created_at DESC);

-- ============================================================================
-- 多智能体协同
--
-- 一次协同 = 一个 agent_run + 若干 agent_node（角色产出、校验结论、工具读取）。
-- node 的 output_hash 是交接完整性的凭据：下游只接受同一次运行、状态成功、
-- 且摘要对得上的上游结果——否则"交接"只是上一轮跑出来的旧结果。
-- ============================================================================

CREATE TABLE IF NOT EXISTS agent_run (
    id             VARCHAR(40)  PRIMARY KEY,
    owner          VARCHAR(200) NOT NULL,
    patient_id     VARCHAR(40)  NOT NULL,
    scenario       VARCHAR(24)  NOT NULL,
    scopes         TEXT         NOT NULL,
    instruction    TEXT         NOT NULL,
    operation_id   VARCHAR(64)  NOT NULL,
    status         VARCHAR(24)  NOT NULL DEFAULT 'QUEUED',
    prompt_version VARCHAR(60)  NOT NULL DEFAULT '',
    graph_version  VARCHAR(60)  NOT NULL DEFAULT '',
    request_fingerprint VARCHAR(64),
    result         TEXT,
    error          VARCHAR(1000),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at     TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ
);

-- 幂等指纹独立成列：result 在执行结束会被最终结果覆盖，
-- 拿它当指纹会让"执行后重复提交"被误判成换请求。
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS request_fingerprint VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS ux_agent_run_op
    ON agent_run (owner, patient_id, operation_id);
CREATE INDEX IF NOT EXISTS idx_agent_run_owner
    ON agent_run (owner, patient_id, created_at DESC);

CREATE TABLE IF NOT EXISTS agent_node (
    id          VARCHAR(40)  PRIMARY KEY,
    run_id      VARCHAR(40)  NOT NULL,
    role        VARCHAR(60),
    kind        VARCHAR(24)  NOT NULL,
    name        VARCHAR(120) NOT NULL,
    status      VARCHAR(24)  NOT NULL DEFAULT 'running',
    output      TEXT,
    output_hash VARCHAR(64),
    summary     VARCHAR(1000),
    error       VARCHAR(200),
    ordinal     INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_agent_node_run
    ON agent_node (run_id, ordinal);

-- 业务实例 ↔ 模型配置版本的绑定。
-- 只存引用不存密钥：执行时按 config_id/version/credential_ref/credential_version
-- 回 model_route_versions 校验并解密。管理员中途改路由不影响在途任务。
CREATE TABLE IF NOT EXISTS model_route_snapshot (
    id            VARCHAR(220) PRIMARY KEY,
    biz_type      VARCHAR(60)  NOT NULL,
    biz_id        VARCHAR(80)  NOT NULL,
    pipeline_key  VARCHAR(40)  NOT NULL,
    snapshot_json TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_mrs_biz ON model_route_snapshot (biz_type, biz_id);

-- ============================================================================
-- Agent Run 事件流与控制面（对齐原工程 agent_runs / agent_run_events）。
-- event_seq 是「每个 run 自己的单调计数器」，不是全局序列：客户端带 after_seq
-- 增量轮询，重复/补发都靠它判定，所以 (run_id, seq) 必须唯一。
-- version 是控制面的乐观锁：cancel/retry 带 expectedVersion，
-- 防止「页面停在旧状态」的一次点击把新一轮执行取消掉。
-- ============================================================================

ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS event_seq INT NOT NULL DEFAULT 0;
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 1;
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS deadline_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS agent_run_event (
    id         VARCHAR(40)  PRIMARY KEY,
    run_id     VARCHAR(40)  NOT NULL,
    seq        INT          NOT NULL,
    type       VARCHAR(64)  NOT NULL,
    node_id    VARCHAR(40),
    status     VARCHAR(24),
    detail     TEXT,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_agent_run_event_seq UNIQUE (run_id, seq)
);
CREATE INDEX IF NOT EXISTS idx_agent_run_event_seq ON agent_run_event (run_id, seq);

-- ============================================================================
-- Agent Lab：工具目录与人工协议校验的回执（对齐原工程 agent_mcp_receipts）。
-- 只存元数据与哈希，不存患者正文、工具输出原文或凭据。
-- running 先落库再执行：进程崩溃后视图显示 interrupted，至少知道这一步开始过。
-- ============================================================================

CREATE TABLE IF NOT EXISTS agent_mcp_receipt (
    id           VARCHAR(40)  PRIMARY KEY,
    owner        VARCHAR(200) NOT NULL,
    patient_id   VARCHAR(40)  NOT NULL,
    kind         VARCHAR(16)  NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'running',
    request      TEXT,
    detail       TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_agent_mcp_receipt_owner
    ON agent_mcp_receipt (owner, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_mcp_receipt_patient
    ON agent_mcp_receipt (owner, patient_id, created_at DESC);

-- ============================================================================
-- QA 书签 / 备忘（对齐原工程 qa_memos）：显式收藏才产生，来源消息唯一。
-- evidence_snapshot / source_versions 是「回答当时」的冻结副本，不随源数据变化。
-- ============================================================================

CREATE TABLE IF NOT EXISTS qa_memo (
    id                 VARCHAR(40)  PRIMARY KEY,
    owner              VARCHAR(200) NOT NULL,
    patient_id         VARCHAR(40)  NOT NULL,
    source_message_id  VARCHAR(40)  NOT NULL,
    question           TEXT,
    content            TEXT         NOT NULL,
    evidence_snapshot  TEXT,
    source_versions    TEXT,
    content_hash       VARCHAR(64)  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- 同一条助手消息重复收藏必须幂等：唯一约束是最后一道防线，
-- 服务层先查后插只是为了给出友好返回，不能替代它。
CREATE UNIQUE INDEX IF NOT EXISTS ux_qa_memo_message
    ON qa_memo (source_message_id);
CREATE INDEX IF NOT EXISTS idx_qa_memo_patient
    ON qa_memo (owner, patient_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_qa_memo_owner
    ON qa_memo (owner, created_at DESC);

-- ============================================================================
-- 单路 RAG 诊断（对齐原工程 qa_diagnostics 主干）：只跑检索一路，不生成综合回答。
-- status 是唯一真相来源；result / draft 是终态的补充，失败也保留已拿到的片段。
-- ============================================================================

CREATE TABLE IF NOT EXISTS qa_diagnostic (
    id               VARCHAR(40)  PRIMARY KEY,
    owner            VARCHAR(200) NOT NULL,
    patient_id       VARCHAR(40)  NOT NULL,
    binding_id       VARCHAR(40)  NOT NULL,
    conversation_id  VARCHAR(40),
    question         TEXT         NOT NULL,
    operation_key    VARCHAR(64)  NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'QUEUED',
    expected_version INTEGER,
    result           TEXT,
    draft            TEXT,
    error_code       VARCHAR(64),
    error_message    VARCHAR(1000),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_qa_diagnostic_operation
    ON qa_diagnostic (operation_key);
CREATE INDEX IF NOT EXISTS idx_qa_diagnostic_conversation
    ON qa_diagnostic (owner, conversation_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_qa_diagnostic_owner
    ON qa_diagnostic (owner, created_at DESC);

-- ============================================================================
-- 患者资料录入审阅草稿（对齐原工程 patient_draft_api 的 ReviewDraft）。
-- 草稿绝不改动临床修订与确认状态：这里只有暂存区，没有提交/确认，
-- 正式写入仍走 /exams/{examId}/confirm。
-- ============================================================================

CREATE TABLE IF NOT EXISTS patient_review_draft (
    id            VARCHAR(40)  PRIMARY KEY,
    owner         VARCHAR(200) NOT NULL,
    exam_id       VARCHAR(40)  NOT NULL,
    scope         VARCHAR(16)  NOT NULL,
    base_revision INT          NOT NULL,
    version       INT          NOT NULL,
    payload       TEXT         NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_patient_review_draft UNIQUE (owner, exam_id, scope)
);
CREATE INDEX IF NOT EXISTS idx_patient_review_draft_exam
    ON patient_review_draft (owner, exam_id);

-- ============================================================================
-- 记忆 Agent 摘录候选（收敛版：不启 STDIO 子进程，候选先 PENDING，发布才落凭据）。
-- observations 存「来源快照 JSON 文档」：候选数组 + 随访原文 + 发布凭据同一行原子落库，
-- 避免候选与它引用的原文分处两处而出现「引用已变、候选还留着」。
-- ============================================================================

CREATE TABLE IF NOT EXISTS memory_agent_note (
    id             VARCHAR(40)  PRIMARY KEY,
    owner          VARCHAR(200) NOT NULL,
    patient_id     VARCHAR(40)  NOT NULL,
    job_id         VARCHAR(40),
    feedback_id    VARCHAR(40),
    operation_id   VARCHAR(80)  NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    observations   TEXT         NOT NULL,
    candidate_hash VARCHAR(64)  NOT NULL,
    source_hash    VARCHAR(64)  NOT NULL,
    model_node_id  VARCHAR(80),
    memory_id      VARCHAR(40),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    CONSTRAINT uk_memory_agent_note_operation UNIQUE (owner, patient_id, operation_id)
);
CREATE INDEX IF NOT EXISTS idx_memory_agent_note_patient
    ON memory_agent_note (owner, patient_id, created_at DESC);

-- ============================================================================
-- 模型用量与成本（一行 = 一次调用）。
-- 不做日聚合表：明细才能回答「昨天的成本是哪次重试抬上去的」，聚合随时可以算。
-- cost_micros 允许 NULL —— 没配单价的模型不猜价格（猜出来的总额看着像真的，更难查）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS model_usage (
    id                VARCHAR(40)  PRIMARY KEY,
    scope_key         VARCHAR(120) NOT NULL,
    provider_id       VARCHAR(80),
    model             VARCHAR(120) NOT NULL,
    prompt_tokens     INTEGER      NOT NULL DEFAULT 0,
    completion_tokens INTEGER      NOT NULL DEFAULT 0,
    total_tokens      INTEGER      NOT NULL DEFAULT 0,
    cost_micros       BIGINT,
    currency          VARCHAR(8)   NOT NULL DEFAULT 'CNY',
    trace_id          VARCHAR(64),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- 命中上游前缀缓存的输入 token 数（含在 prompt_tokens 内）。
-- 不存这一列，cost_micros 就成了"黑盒结果"：缓存价与未命中价差 50 倍，
-- 事后没人能只凭 prompt/completion 复算出成本。
ALTER TABLE model_usage ADD COLUMN IF NOT EXISTS cached_prompt_tokens INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_model_usage_created ON model_usage (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_model_usage_scope ON model_usage (scope_key, created_at DESC);
