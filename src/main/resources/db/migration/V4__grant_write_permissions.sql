-- =============================================================================
-- V4 补充写操作权限
-- 由 Flyway 管理。**已发布的迁移脚本不可修改**，否则 checksum 校验会失败。
--
-- 背景：V3 只授予了各模块的 :list（只读）权限。M1 业务底座引入写操作后，
-- 需要为管理员补充 :save 权限，否则写接口会被 @SaCheckPermission 拦成 403。
--
-- 客户归入销售域（biz:sales:*），与 V3 的域名保持一致，避免新增过多权限码。
-- =============================================================================

UPDATE sys_role
SET permissions = CONCAT(permissions, ',biz:product:save,biz:sales:save,biz:inventory:save')
WHERE ent_code = 'DEMO'
  AND role_code = 'admin'
  AND permissions NOT LIKE '%biz:product:save%';
