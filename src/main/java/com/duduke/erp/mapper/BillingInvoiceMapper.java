package com.duduke.erp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duduke.erp.entity.po.BillingInvoice;

/**
 * 发票数据访问。
 * <p>
 * <b>不加 {@code @InterceptorIgnore}</b>：本表有 {@code ent_code}，
 * 租户条件由插件注入——发票永远只能看本租户的。
 */
public interface BillingInvoiceMapper extends BaseMapper<BillingInvoice> {
}
