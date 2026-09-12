-- =============================================================================
-- V5 知识文档补齐校验和列
-- 由 Flyway 管理。**已发布的迁移脚本不可修改**，变更一律新增版本。
--
-- 背景：M2 知识库链路需要记录文档内容的 SHA-256，用于审计与判断内容是否变化。
-- V1 建 knowledge_document 时未包含该列，故新增本迁移而非改动 V1。
-- =============================================================================

ALTER TABLE knowledge_document
    ADD COLUMN IF NOT EXISTS checksum_sha256 VARCHAR(64);

COMMENT ON COLUMN knowledge_document.checksum_sha256 IS '文档内容 SHA-256，用于审计与内容变更判断';
