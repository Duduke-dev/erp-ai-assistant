package com.duduke.erp.entity.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 销售订单明细展示对象。
 */
public record SalesOrderItemVO(
        Long id,
        Long productId,
        String productName,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal amount,
        LocalDateTime createdAt) {
}
