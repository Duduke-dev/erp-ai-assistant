package com.duduke.erp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duduke.erp.entity.po.BillingPlan;

/**
 * 套餐数据访问。
 * <p>
 * <b>不加 {@code @InterceptorIgnore}</b>：{@code billing_plan} 是全局配置表，
 * 已在 {@code app.tenant.ignore-tables} 中，租户插件本就跳过它，再加是冗余。
 */
public interface BillingPlanMapper extends BaseMapper<BillingPlan> {
}
