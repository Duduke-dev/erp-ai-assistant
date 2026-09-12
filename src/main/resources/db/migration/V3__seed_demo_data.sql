-- =============================================================================
-- V3 演示数据
-- 由 Flyway 管理。**已发布的迁移脚本不可修改**，否则 checksum 校验会失败。
--
-- 关于幂等：Flyway 保证本脚本只执行一次，理论上无需幂等处理。
-- 但存量库（此前由 Java 初始化器写过这批数据）会与唯一约束冲突，
-- 因此统一使用 ON CONFLICT DO NOTHING，保证对已有库安全。
--
-- 演示账号（明文密码均为 123456，存储的是 BCrypt 散列，强度 10）：
--   DEMO / admin   —— 全权限
--   DEMO / viewer  —— 只读，且**故意不含 biz:product:list**，用于鉴权回归测试
-- =============================================================================

-- 租户
INSERT INTO tenant (ent_code, ent_name, status)
VALUES ('DEMO', '演示租户', 'active')
ON CONFLICT DO NOTHING;

-- 角色
INSERT INTO sys_role (ent_code, role_code, role_name, permissions)
VALUES ('DEMO', 'admin', '系统管理员',
        'biz:product:list,biz:sales:list,biz:purchase:list,biz:inventory:list,'
        'biz:production:list,biz:quality:list,biz:finance:list,biz:aftersale:list,'
        'biz:outsourcing:list,tool:manage,knowledge:manage,billing:manage')
ON CONFLICT DO NOTHING;

INSERT INTO sys_role (ent_code, role_code, role_name, permissions)
VALUES ('DEMO', 'viewer', '只读访客', 'biz:sales:list')
ON CONFLICT DO NOTHING;

-- 账号
INSERT INTO sys_user (ent_code, username, password_hash, real_name, status)
VALUES ('DEMO', 'admin',
        '$2a$10$PiVbdn8ByQVD3oFgHvcdpOBjBglWKGgCBh2lXqIXEHQWIr9zXS9Ve',
        '系统管理员', 'active')
ON CONFLICT DO NOTHING;

INSERT INTO sys_user (ent_code, username, password_hash, real_name, status)
VALUES ('DEMO', 'viewer',
        '$2a$10$PiVbdn8ByQVD3oFgHvcdpOBjBglWKGgCBh2lXqIXEHQWIr9zXS9Ve',
        '只读访客', 'active')
ON CONFLICT DO NOTHING;

-- 用户角色关联（user_id / role_id 需子查询取得）
INSERT INTO sys_user_role (ent_code, user_id, role_id)
SELECT 'DEMO', u.id, r.id
FROM sys_user u, sys_role r
WHERE u.ent_code = 'DEMO' AND u.username = 'admin'
  AND r.ent_code = 'DEMO' AND r.role_code = 'admin'
ON CONFLICT DO NOTHING;

INSERT INTO sys_user_role (ent_code, user_id, role_id)
SELECT 'DEMO', u.id, r.id
FROM sys_user u, sys_role r
WHERE u.ent_code = 'DEMO' AND u.username = 'viewer'
  AND r.ent_code = 'DEMO' AND r.role_code = 'viewer'
ON CONFLICT DO NOTHING;
