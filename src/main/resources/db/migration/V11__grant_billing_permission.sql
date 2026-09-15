-- 计费查询权限。
--
-- 为什么单独一个码：账户与用量是「本租户自己的经营数据」，
-- 与业务写权限（biz:*:save）性质不同，也不该跟着 Tool 权限一起发。
-- admin 与 viewer 都给：查自己的用量不涉及越权，viewer 只读也应当能看。
--
-- 与 V8/V10 同样的写法：追加前先判断，避免重复执行时把权限串写重复。
UPDATE sys_role
SET permissions = CONCAT(permissions, ',billing:query')
WHERE ent_code = 'DEMO'
  AND role_code IN ('admin', 'viewer')
  AND permissions NOT LIKE '%billing:query%';
