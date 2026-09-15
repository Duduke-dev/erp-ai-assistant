-- 本租户用户管理权限。
--
-- 只给 admin，不给 viewer：能新建用户、分配角色，等于能授予权限——
-- 这是提权面，不是普通只读能力。
--
-- 注意范围：本权限作用于**本租户**的用户。用户表 sys_user 有 ent_code
-- 且不在 ignore-tables 中，租户插件会自动把范围限制在当前租户内，
-- 因此不需要也不该用 @InterceptorIgnore。
UPDATE sys_role
SET permissions = CONCAT(permissions, ',user:list')
WHERE ent_code = 'DEMO'
  AND role_code = 'admin'
  AND permissions NOT LIKE '%user:list%';

UPDATE sys_role
SET permissions = CONCAT(permissions, ',user:save')
WHERE ent_code = 'DEMO'
  AND role_code = 'admin'
  AND permissions NOT LIKE '%user:save%';
