-- =============================================================================
-- V10 授予动态 SQL Tool 的调用权限
-- 由 Flyway 管理。**已发布的迁移脚本不可修改**，变更一律新增版本。
--
-- 为什么单独一个权限码，而不复用模块级 tool:xxx:query：
--   模块级权限对应的是我们逐个审过的固定查询（如 getSalesOrders），风险面已知。
--   动态 Tool 是管理端配置的**任意单层 SELECT**，可查任意表，风险面完全不同。
--   因此设一道独立闸门，只授予「已能定义动态 Tool」的那批人（tool:llm:save），
--   不默认扩散给所有能提问的用户。
--
-- 若后续要放宽给全部能提问的用户，两步即可：
--   ① 从 ToolPermissionCatalog 里去掉对动态 Tool 的权限要求；
--   ② 本迁移无需回滚（多一个未使用的权限码无害）。
-- =============================================================================

UPDATE sys_role
SET permissions = CONCAT(permissions, ',tool:dynamic:query')
WHERE ent_code = 'DEMO'
  AND role_code = 'admin'
  AND permissions NOT LIKE '%tool:dynamic:query%';
