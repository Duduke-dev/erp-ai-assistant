-- =============================================================================
-- V18 授予知识库检索权限（tool:knowledge:query）
-- =============================================================================
-- 背景：新增知识库检索 Tool（search_knowledge_base），让模型自己决定
-- 「这轮要不要查资料」，取代原先「挂了 Advisor 就每轮强制检索」的做法。
--
-- 为什么必须授权：ToolPermissionCatalog 的注册是 **fail-closed** 的——
-- 没有权限声明的 Tool 会被注册表拒绝注册，模型永远看不到它，
-- 而且只在启动日志里留一条 ERROR。表现为「工具像是根本没生效」。
--
-- 授权范围：仅 DEMO 租户的 admin。viewer 是只读角色，是否也该能检索资料，
-- 留给后续按需决定——这里从保守出发，先不给。
-- =============================================================================

UPDATE sys_role
SET permissions = CONCAT(permissions, ',tool:knowledge:query')
WHERE ent_code = 'DEMO'
  AND role_code = 'admin'
  AND permissions NOT LIKE '%tool:knowledge:query%';
