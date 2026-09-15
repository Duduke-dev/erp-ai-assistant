package com.duduke.erp.entity.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 交易流水展示对象。
 *
 * @param type         recharge / deduction / gift
 * @param amount       变动金额，扣费为负
 * @param balanceAfter 变动后余额，便于逐笔还原对账
 */
public record BillingTransactionVO(
        Long id,
        String transactionNo,
        String type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        Long tokens,
        String remark,
        LocalDateTime createdAt) {
}
