-- =============================================================================
-- V8 补充 Tool Calling 所需的结构与权限
-- 由 Flyway 管理。**已发布的迁移脚本不可修改**，变更一律新增版本。
--
-- 背景：M3 引入 Tool Calling。
--
-- 一、tool_call_log 的两列缺口
--   V1 建表时漏了 user_id 与 mode —— 参考实现有这两列。补上才能回答
--   「谁、在哪种模式下、调了这个 Tool」，也才能按模式分析 Tool 命中率。
--   二者都允许为 null：异步收口路径上未必拿得到完整的调用者上下文，
--   为此让整条插入失败不划算（调用日志是旁路，不能影响问答主链路）。
--
-- 二、动态 Tool 管理权限
--   与对话权限分开授予：能问（chat:ask）不等于能定义动态 Tool。
--   按项目约定「写操作用 :save、与 :list 分开」，读与写给不同权限码。
-- =============================================================================

ALTER TABLE tool_call_log ADD COLUMN IF NOT EXISTS user_id BIGINT;
ALTER TABLE tool_call_log ADD COLUMN IF NOT EXISTS mode    VARCHAR(16);

COMMENT ON COLUMN tool_call_log.user_id IS '调用者主键，异步路径取不到时为 null';
COMMENT ON COLUMN tool_call_log.mode    IS '本轮模式：auto / knowledge';

-- 动态 Tool 管理：查询列表
UPDATE sys_role
SET permissions = CONCAT(permissions, ',tool:llm:list')
WHERE ent_code = 'DEMO'
  AND role_code = 'admin'
  AND permissions NOT LIKE '%tool:llm:list%';

-- 动态 Tool 管理：新建 / 更新 / 删除 / 触发刷新
UPDATE sys_role
SET permissions = CONCAT(permissions, ',tool:llm:save')
WHERE ent_code = 'DEMO'
  AND role_code = 'admin'
  AND permissions NOT LIKE '%tool:llm:save%';

-- Tool 调用日志查询
UPDATE sys_role
SET permissions = CONCAT(permissions, ',tool:log:list')
WHERE ent_code = 'DEMO'
  AND role_code = 'admin'
  AND permissions NOT LIKE '%tool:log:list%';
