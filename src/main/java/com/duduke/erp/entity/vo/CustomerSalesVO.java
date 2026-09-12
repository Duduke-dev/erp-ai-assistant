package com.duduke.erp.entity.vo;

import java.math.BigDecimal;

/**
 * 按客户汇总的销售数据。
 */
public record CustomerSalesVO(
        Long customerId,
        String customerName,
        Long orderCount,
        BigDecimal totalAmount) {
}
