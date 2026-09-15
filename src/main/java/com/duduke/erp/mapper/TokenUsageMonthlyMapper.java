package com.duduke.erp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duduke.erp.entity.po.TokenUsageMonthly;

/**
 * 月用量数据访问。
 * <p>
 * 同 {@code TokenUsageDailyMapper}：不加 {@code @InterceptorIgnore}，租户条件交给插件。
 */
public interface TokenUsageMonthlyMapper extends BaseMapper<TokenUsageMonthly> {
}
