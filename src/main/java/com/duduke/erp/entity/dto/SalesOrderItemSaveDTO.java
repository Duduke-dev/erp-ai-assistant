package com.duduke.erp.entity.dto;

import java.math.BigDecimal;

/**
 * 销售订单明细入参。
 * <p>
 * 金额（{@code amount}）不由前端传递，由 Service 用「数量 × 单价」计算后落库，
 * 避免前端篡改导致金额与明细不符。
 */
public record SalesOrderItemSaveDTO(
        Long productId,
        String productName,
        BigDecimal quantity,
        BigDecimal unitPrice) {
}
