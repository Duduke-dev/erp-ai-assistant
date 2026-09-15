package com.duduke.erp.entity.dto;

/**
 * 计费账户开户 / 更新入参。
 * <p>
 * 账户在本项目里是<b>每租户一条</b>（唯一索引 {@code uk_billing_account_ent}），
 * 因此用 id 定位不如用「本租户的账户」语义定位；本 DTO 两种场景共用。
 * <p>
 * {@code planCode} 与 {@code monthlyQuota} 同时给出是刻意的：账户持有套餐的<b>快照</b>，
 * 套餐后来调价调额不应追溯影响已签约账户，因此额度落在账户上而不是每次现查套餐。
 */
public record BillingAccountSaveDTO(
        String planCode,
        Long monthlyQuota,
        String status) {
}
