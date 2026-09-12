package com.duduke.erp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duduke.erp.entity.po.Tenant;

/**
 * 租户数据访问。
 * <p>
 * tenant 已加入 app.tenant.ignore-tables，租户插件不注入条件，
 * 但所有查询仍必须自行带上 ent_code。
 */
public interface TenantMapper extends BaseMapper<Tenant> {

}
