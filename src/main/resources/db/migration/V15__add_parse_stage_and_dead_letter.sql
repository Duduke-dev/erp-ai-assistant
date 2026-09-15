-- M2.5 切片 3：解析进度可观测 + 死信可运维。
--
-- ① knowledge_document.stage：把 processing 这一态细化成可观测的阶段。
--    不加百分比——向量化是批量写入，百分比要么假，要么得把 loader 拆成
--    split / store 两步；假进度比没进度更糟。
--    历史行为 NULL 是刻意的：它们没有经历过阶段流转，编一个阶段出来是假数据。
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS stage VARCHAR(32);

-- ② 死信登记表。
--    没有它，死信消息只存在于 RabbitMQ 里：应用内看不见、无法重投、
--    重启后也无从追溯——用户端表现为「上传失败但没人知道为什么」。
--    表带 ent_code 走租户隔离（不在 ignore-tables 中，插件自动注入）。
CREATE TABLE IF NOT EXISTS document_parse_dead_letter (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ent_code          VARCHAR(32)  NOT NULL,
    document_id       VARCHAR(64),
    knowledge_base_id BIGINT,
    version           INT,
    object_key        VARCHAR(512),
    file_name         VARCHAR(256),
    -- 原始消息体，重投时原样发回主队列，不做二次构造
    payload           TEXT         NOT NULL,
    error_message     VARCHAR(1024),
    -- pending / retried / discarded
    status            VARCHAR(16)  NOT NULL DEFAULT 'pending',
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_dlq_ent_status ON document_parse_dead_letter (ent_code, status);

-- ③ 死信处理权限。
--    只给 admin：重投会重置文档状态并重新触发解析，等于改变知识库内容，
--    不是普通只读能力。
UPDATE sys_role
SET permissions = CONCAT(permissions, ',knowledge:dlq:manage')
WHERE ent_code = 'DEMO'
  AND role_code = 'admin'
  AND permissions NOT LIKE '%knowledge:dlq:manage%';
