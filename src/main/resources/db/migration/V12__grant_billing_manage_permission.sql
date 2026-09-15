-- 计费管理权限（套餐与价格规则的维护权）。
--
-- 只给 admin，不给 viewer：
-- billing:query 是「查自己的用量」，而本权限是「改平台的计费配置」——
-- 套餐额度与 token 单价一旦被改，全租户的计费口径随之变化。
-- 两者性质不同，因此不复用同一个码。
--
-- 与 V8/V10/V11 同样的写法：追加前先判断，避免重复执行把权限串写重复。
UPDATE sys_role
SET permissions = CONCAT(permissions, ',billing:manage')
WHERE ent_code = 'DEMO'
  AND role_code = 'admin'
  AND permissions NOT LIKE '%billing:manage%';
