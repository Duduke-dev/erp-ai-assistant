-- =============================================================================
-- V1 平台表结构（PostgreSQL）
-- 由 Flyway 管理。**已发布的迁移脚本不可修改**，否则 checksum 校验会失败。
-- 后续变更请新增 V4__xxx.sql、V5__xxx.sql。
-- 约定：
--   1. 除 billing_plan / billing_price_rule / llm_tool 三张全局配置表外，均带 ent_code
--   2. JSON 列统一用 jsonb（实体侧需配合 Jackson3TypeHandler + autoResultMap）
--   3. 保留 IF NOT EXISTS：使本脚本对「已用旧手工脚本建过表」的存量库也安全
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 租户与权限
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tenant (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    ent_name        VARCHAR(128) NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_tenant_code ON tenant (ent_code);

CREATE TABLE IF NOT EXISTS sys_user (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    username        VARCHAR(64)  NOT NULL,
    password_hash   VARCHAR(128) NOT NULL,
    real_name       VARCHAR(64),
    phone           VARCHAR(32),
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_name ON sys_user (ent_code, username);
CREATE INDEX IF NOT EXISTS idx_sys_user_ent ON sys_user (ent_code);

CREATE TABLE IF NOT EXISTS sys_role (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    role_code       VARCHAR(64)  NOT NULL,
    role_name       VARCHAR(64)  NOT NULL,
    -- 逗号分隔的权限码，Sa-Token 通过 StpInterface 实现读取
    permissions     VARCHAR(2048),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_role_code ON sys_role (ent_code, role_code);

CREATE TABLE IF NOT EXISTS sys_user_role (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    user_id         BIGINT       NOT NULL,
    role_id         BIGINT       NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_role ON sys_user_role (user_id, role_id);

-- ---------------------------------------------------------------------------
-- 对话与消息
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chat_conversation (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    conversation_id VARCHAR(64)  NOT NULL,
    user_id         BIGINT,
    title           VARCHAR(256),
    model_id        VARCHAR(64),
    message_count   INT          NOT NULL DEFAULT 0,
    total_tokens    INT          NOT NULL DEFAULT 0,
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_conversation_id ON chat_conversation (ent_code, conversation_id);
CREATE INDEX IF NOT EXISTS idx_conversation_ent_time ON chat_conversation (ent_code, updated_at DESC);

CREATE TABLE IF NOT EXISTS chat_message (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    conversation_id VARCHAR(64)  NOT NULL,
    role            VARCHAR(16)  NOT NULL,
    content         TEXT,
    model_id        VARCHAR(64),
    mode            VARCHAR(16),
    prompt_tokens   INT          NOT NULL DEFAULT 0,
    completion_tokens INT        NOT NULL DEFAULT 0,
    total_tokens    INT          NOT NULL DEFAULT 0,
    elapsed_ms      BIGINT       NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'completed',
    -- 版本化图表协议 JSON，失败或无需图表时为 NULL
    chart_spec      JSONB,
    -- 本轮实际召回的引用证据 JSON 数组
    rag_citations   JSONB,
    tool_calls      JSONB,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_chat_message_conv ON chat_message (ent_code, conversation_id, id);

-- ---------------------------------------------------------------------------
-- 知识库与文档
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS knowledge_base (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    is_default      BOOLEAN      NOT NULL DEFAULT FALSE,
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_knowledge_base_ent ON knowledge_base (ent_code);

CREATE TABLE IF NOT EXISTS knowledge_document (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    knowledge_base_id BIGINT     NOT NULL,
    document_id     VARCHAR(64)  NOT NULL,
    title           VARCHAR(256) NOT NULL,
    version         INT          NOT NULL DEFAULT 1,
    -- processing / ready / failed / superseded / deleted
    status          VARCHAR(16)  NOT NULL DEFAULT 'processing',
    -- 对象存储位置，面向协议不绑定产品，可平滑切换 RustFS / MinIO / S3
    bucket          VARCHAR(128),
    object_key      VARCHAR(512),
    file_size       BIGINT,
    content_type    VARCHAR(128),
    chunk_count     INT          NOT NULL DEFAULT 0,
    -- 生成向量的模型指纹，检索时强制比对，防止混用
    embedding_model VARCHAR(128),
    error_message   VARCHAR(1024),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_document_version ON knowledge_document (document_id, version);
CREATE INDEX IF NOT EXISTS idx_document_ent_kb ON knowledge_document (ent_code, knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_document_ready ON knowledge_document (ent_code, knowledge_base_id, status);

-- ---------------------------------------------------------------------------
-- 动态 LLM Tool（全局配置表，不按租户隔离；执行时注入 ent_code）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS llm_tool (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tool_name       VARCHAR(64)  NOT NULL,
    tool_desc       VARCHAR(500) NOT NULL,
    -- 入参 JSON Schema，直接下发给 LLM 作为 function calling 参数定义
    input_schema    TEXT         NOT NULL,
    sql_template    TEXT         NOT NULL,
    -- 主表别名，租户注入时拼成 alias.ent_code；为空则用裸列
    table_alias     VARCHAR(32),
    result_limit    INT          NOT NULL DEFAULT 50,
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    remark          VARCHAR(512),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_llm_tool_name ON llm_tool (tool_name);

CREATE TABLE IF NOT EXISTS tool_call_log (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    conversation_id VARCHAR(64),
    message_id      BIGINT,
    trace_id        VARCHAR(64),
    tool_name       VARCHAR(64)  NOT NULL,
    -- code / database
    tool_source     VARCHAR(16)  NOT NULL,
    model_name      VARCHAR(64),
    arguments       JSONB,
    status          VARCHAR(16)  NOT NULL,
    elapsed_ms      BIGINT       NOT NULL DEFAULT 0,
    result_count    INT          NOT NULL DEFAULT 0,
    error_summary   VARCHAR(1024),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_tool_call_log_ent_time ON tool_call_log (ent_code, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_tool_call_log_trace ON tool_call_log (trace_id);

-- ---------------------------------------------------------------------------
-- 计费
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS billing_plan (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plan_code       VARCHAR(64)  NOT NULL,
    plan_name       VARCHAR(128) NOT NULL,
    monthly_quota   BIGINT       NOT NULL DEFAULT 0,
    price           NUMERIC(18,2) NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_billing_plan_code ON billing_plan (plan_code);

CREATE TABLE IF NOT EXISTS billing_price_rule (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    model_name      VARCHAR(64)  NOT NULL,
    -- 每千 token 单价
    input_price     NUMERIC(18,6) NOT NULL DEFAULT 0,
    output_price    NUMERIC(18,6) NOT NULL DEFAULT 0,
    effective_date  DATE         NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_price_rule_model ON billing_price_rule (model_name, effective_date);

CREATE TABLE IF NOT EXISTS billing_account (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    plan_code       VARCHAR(64)  NOT NULL,
    balance         NUMERIC(18,2) NOT NULL DEFAULT 0,
    monthly_quota   BIGINT       NOT NULL DEFAULT 0,
    used_tokens     BIGINT       NOT NULL DEFAULT 0,
    -- active / suspended / arrears
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_billing_account_ent ON billing_account (ent_code);

CREATE TABLE IF NOT EXISTS billing_transaction (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    transaction_no  VARCHAR(64)  NOT NULL,
    -- recharge / deduction / gift
    type            VARCHAR(16)  NOT NULL,
    amount          NUMERIC(18,2) NOT NULL DEFAULT 0,
    balance_after   NUMERIC(18,2) NOT NULL DEFAULT 0,
    tokens          BIGINT       NOT NULL DEFAULT 0,
    remark          VARCHAR(512),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_billing_tx_no ON billing_transaction (ent_code, transaction_no);
CREATE INDEX IF NOT EXISTS idx_billing_tx_ent_time ON billing_transaction (ent_code, created_at DESC);

CREATE TABLE IF NOT EXISTS billing_invoice (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    period          VARCHAR(7)   NOT NULL,
    total_tokens    BIGINT       NOT NULL DEFAULT 0,
    total_amount    NUMERIC(18,2) NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_billing_invoice_period ON billing_invoice (ent_code, period);

CREATE TABLE IF NOT EXISTS token_usage_daily (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    usage_date      DATE         NOT NULL,
    model_name      VARCHAR(64),
    prompt_tokens   BIGINT       NOT NULL DEFAULT 0,
    completion_tokens BIGINT     NOT NULL DEFAULT 0,
    total_tokens    BIGINT       NOT NULL DEFAULT 0,
    request_count   INT          NOT NULL DEFAULT 0,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_usage_daily ON token_usage_daily (ent_code, usage_date, model_name);

CREATE TABLE IF NOT EXISTS token_usage_monthly (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code        VARCHAR(32)  NOT NULL,
    period          VARCHAR(7)   NOT NULL,
    model_name      VARCHAR(64),
    total_tokens    BIGINT       NOT NULL DEFAULT 0,
    request_count   INT          NOT NULL DEFAULT 0,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_usage_monthly ON token_usage_monthly (ent_code, period, model_name);
