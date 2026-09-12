package com.duduke.erp.entity.vo;

import java.math.BigDecimal;

/**
 * 按产品汇总的销售数据。
 */
public record ProductSalesVO(
        Long productId,
        String productName,
        BigDecimal totalQuantity,
        BigDecimal totalAmount) {
}
