package com.duduke.erp.entity.dto;

import java.math.BigDecimal;

/**
 * 账户充值入参。
 * <p>
 * 金额必须为正：这个接口只表达「加钱」，扣费走系统内部的 deduction，
 * 不让一个入参同时承担加钱与扣钱两种语义——那正是财务接口最容易被误用的地方。
 */
public record BillingRechargeDTO(
        BigDecimal amount,
        String remark) {
}
