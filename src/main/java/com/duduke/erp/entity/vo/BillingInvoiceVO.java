package com.duduke.erp.entity.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 发票展示对象。
 *
 * @param period      账期 yyyy-MM
 * @param totalTokens 该账期合计 token
 * @param totalAmount 该账期合计金额
 */
public record BillingInvoiceVO(
        Long id,
        String period,
        Long totalTokens,
        BigDecimal totalAmount,
        LocalDateTime createdAt) {
}
