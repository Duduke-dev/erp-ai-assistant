package com.duduke.erp.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duduke.erp.entity.po.BillingAccount;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 计费账户数据访问。
 * <p>
 * 常规查询<b>不加 {@code @InterceptorIgnore}</b>：{@code billing_account} 有 {@code ent_code}
 * 且不在 ignore-tables 中，租户条件由插件自动注入。手写反而多一处漏写的风险。
 */
public interface BillingAccountMapper extends BaseMapper<BillingAccount> {

    /**
     * 统计有多少账户引用了某套餐 —— <b>跨全部租户</b>。
     * <p>
     * 这是唯一一处刻意的 {@code @InterceptorIgnore}：套餐是全局配置，
     * 「还有谁在用这个套餐」本质上就是跨租户的问题；按租户过滤会漏掉其它租户的引用，
     * 于是删掉一个仍被使用的套餐，留下悬空的 {@code plan_code}。
     * <p>
     * <b>只返回计数、不返回任何行数据</b>，因此跨租户也不构成数据泄漏。
     * 这也正是「禁用租户过滤」在这里可以接受的前提。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT COUNT(*) FROM billing_account WHERE plan_code = #{planCode}")
    long countByPlanCodeAcrossTenants(@Param("planCode") String planCode);

}
