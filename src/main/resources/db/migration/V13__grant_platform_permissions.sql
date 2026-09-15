-- 平台管理权限（租户维护）。
--
-- 只给 admin，且与 billing:manage 分开：租户是**跨租户的顶层资源**，
-- 能改计费配置不等于该看到所有租户的清单——两者风险面不同。
UPDATE sys_role
SET permissions = CONCAT(permissions, ',platform:tenant:list')
WHERE ent_code = 'DEMO'
  AND role_code = 'admin'
  AND permissions NOT LIKE '%platform:tenant:list%';

UPDATE sys_role
SET permissions = CONCAT(permissions, ',platform:tenant:manage')
WHERE ent_code = 'DEMO'
  AND role_code = 'admin'
  AND permissions NOT LIKE '%platform:tenant:manage%';
