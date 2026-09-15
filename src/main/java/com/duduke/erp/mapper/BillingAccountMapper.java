package com.duduke.erp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duduke.erp.entity.po.BillingAccount;

/**
 * 计费账户数据访问。
 * <p>
 * <b>不加 {@code @InterceptorIgnore}</b>：{@code billing_account} 有 {@code ent_code}
 * 且不在 ignore-tables 中，租户条件由插件自动注入。手写反而多一处漏写的风险。
 */
public interface BillingAccountMapper extends BaseMapper<BillingAccount> {
}
