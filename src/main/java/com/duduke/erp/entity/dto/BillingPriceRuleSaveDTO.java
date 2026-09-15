package com.duduke.erp.entity.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 价格规则入参。
 * <p>
 * <b>不提供更新接口</b>：价格是带生效日期的历史记录，
 * 「改一条历史价格」没有正确语义——要调价就新增一条生效日期更晚的记录。
 * 因此这里只用于新增，配合删除（删错录的记录）。
 */
public record BillingPriceRuleSaveDTO(
        String modelName,
        BigDecimal inputPrice,
        BigDecimal outputPrice,
        LocalDate effectiveDate) {
}
