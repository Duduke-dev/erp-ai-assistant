package com.duduke.erp.entity.vo;

import java.math.BigDecimal;

/**
 * 套餐展示对象。
 */
public record BillingPlanVO(
        Long id,
        String planCode,
        String planName,
        Long monthlyQuota,
        BigDecimal price) {
}
