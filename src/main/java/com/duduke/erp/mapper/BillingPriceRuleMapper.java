package com.duduke.erp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duduke.erp.entity.po.BillingPriceRule;

/**
 * 价格规则数据访问。
 * <p>
 * <b>不加 {@code @InterceptorIgnore}</b>：本表是全局配置表，
 * 已在 {@code app.tenant.ignore-tables} 中。
 */
public interface BillingPriceRuleMapper extends BaseMapper<BillingPriceRule> {
}
