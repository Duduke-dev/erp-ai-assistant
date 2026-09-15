package com.duduke.erp.entity.vo;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 价格规则展示对象。
 */
public record BillingPriceRuleVO(
        Long id,
        String modelName,
        BigDecimal inputPrice,
        BigDecimal outputPrice,
        LocalDate effectiveDate) {
}
