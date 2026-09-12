package com.duduke.erp.entity.vo;

import java.math.BigDecimal;

/**
 * 按月汇总的销售数据。
 * <p>
 * {@code month} 格式为 {@code YYYY-MM}，由 SQL 的 {@code TO_CHAR} 生成，
 * 保持字符串而非日期类型，避免前端再处理时区。
 */
public record MonthlySalesVO(
        String month,
        Long orderCount,
        BigDecimal totalAmount) {
}
