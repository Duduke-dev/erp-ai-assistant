-- =============================================================================
-- V7 补充对话权限
-- 由 Flyway 管理。**已发布的迁移脚本不可修改**，变更一律新增版本。
--
-- 背景：M4 引入对话接口（ChatController），需要新的权限码 chat:ask。
-- 与知识库权限分开授予：能问（chat:ask）不等于能管理知识库（knowledge:manage）。
-- =============================================================================

UPDATE sys_role
SET permissions = CONCAT(permissions, ',chat:ask')
WHERE ent_code = 'DEMO'
  AND role_code = 'admin'
  AND permissions NOT LIKE '%chat:ask%';
