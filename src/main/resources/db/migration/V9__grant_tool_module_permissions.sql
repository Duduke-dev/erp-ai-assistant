-- =============================================================================
-- V9 授予业务 Tool 的模块级查询权限
-- 由 Flyway 管理。**已发布的迁移脚本不可修改**，变更一律新增版本。
--
-- 背景：M3 决定 Tool 层加权限校验，粒度＝按业务模块，生效方式＝**注册时过滤**：
--   每轮问答只把当前用户<b>有权限的</b> Tool 交给模型，无权限的工具模型根本看不到，
--   不存在「选中了却调不动」的反复重试。
--
-- 为什么与 V8 分开：V8 授的是「动态 Tool 的<b>管理</b>权限」（tool:llm:list/save），
-- 那是管理端的事；这里授的是「业务 Tool 的<b>使用</b>权限」，是对话链路的事。
-- 两者授权对象与风险面都不同，分开演进。
--
-- 命名沿用既有风格（biz:product:list / chat:ask）：tool:<模块>:query。
-- V9 一次性把 8 个模块都授出去，避免后续每加一个模块就补一次迁移。
-- =============================================================================

UPDATE sys_role
SET permissions = CONCAT(permissions,
        ',tool:sales:query,tool:purchase:query,tool:production:query,tool:quality:query',
        ',tool:warehouse:query,tool:finance:query,tool:aftersales:query,tool:outsourcing:query')
WHERE ent_code = 'DEMO'
  AND role_code = 'admin'
  AND permissions NOT LIKE '%tool:sales:query%';
