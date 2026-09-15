package com.duduke.erp.entity.dto;

import java.math.BigDecimal;

/**
 * 套餐新增 / 更新入参。
 * <p>
 * 新增与更新共用：新增走 {@code POST}，更新走 {@code PUT /{id}}，主键来自 URL，故不带 id。
 * <p>
 * <b>{@code planCode} 只在新增时生效</b>：更新时不允许改编码——
 * 账户表用 {@code plan_code} 关联套餐，改编码会让既有账户指向一个不存在的套餐。
 */
public record BillingPlanSaveDTO(
        String planCode,
        String planName,
        Long monthlyQuota,
        BigDecimal price) {
}
