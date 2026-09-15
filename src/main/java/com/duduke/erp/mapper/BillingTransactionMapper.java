package com.duduke.erp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duduke.erp.entity.po.BillingTransaction;

/**
 * 交易流水数据访问。
 * <p>
 * <b>不加 {@code @InterceptorIgnore}</b>：本表有 {@code ent_code}，
 * 租户条件由插件注入——查余额与流水永远只该看本租户的。
 */
public interface BillingTransactionMapper extends BaseMapper<BillingTransaction> {
}
