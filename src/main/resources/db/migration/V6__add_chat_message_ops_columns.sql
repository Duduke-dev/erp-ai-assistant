-- =============================================================================
-- V6 对话消息补齐运营字段
-- 由 Flyway 管理。**已发布的迁移脚本不可修改**，变更一律新增版本。
--
-- 背景：M4 对话编排需要在消息上记录「本轮用了哪个知识库、召回多少分片、
-- 工具调用几次、失败原因」，供前端展示与问题排查。V1 建表时未包含这些列。
--
-- 状态枚举沿用 V1 的默认值 completed / cancelled / failed，
-- 不引入参考实现的 success / error 两套命名，避免映射层来回转换。
-- =============================================================================

ALTER TABLE chat_message
    ADD COLUMN IF NOT EXISTS knowledge_base_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS tool_calls_count  INT     NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS rag_doc_count     INT     NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS error_message     VARCHAR(1024);

COMMENT ON COLUMN chat_message.knowledge_base_id IS '本轮实际使用的知识库主键';
COMMENT ON COLUMN chat_message.rag_doc_count     IS '本轮通过资格过滤的召回分片数';
COMMENT ON COLUMN chat_message.tool_calls_count  IS '本轮工具调用次数';
COMMENT ON COLUMN chat_message.error_message     IS '失败原因摘要，status = failed 时有值';
