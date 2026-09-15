package com.duduke.erp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duduke.erp.entity.po.TokenUsageDaily;

/**
 * 日用量数据访问。
 * <p>
 * <b>不加 {@code @InterceptorIgnore}</b>：本表有 {@code ent_code}，租户条件由插件注入。
 * 区间汇总用 wrapper 查回后在内存聚合，而不是写
 * {@code SUM(...) GROUP BY ...} 的自定义 SQL——
 * 后者要与租户插件的改写协同，容易踩到「条件注入位置不对」的静默错误，
 * 而本表按天最多 366 行/年/租户，内存聚合的代价可以忽略。
 */
public interface TokenUsageDailyMapper extends BaseMapper<TokenUsageDaily> {
}
