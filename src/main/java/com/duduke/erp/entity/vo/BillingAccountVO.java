package com.duduke.erp.entity.vo;

import java.math.BigDecimal;

/**
 * 计费账户展示对象。
 *
 * @param planCode       当前套餐编码
 * @param balance        账户余额
 * @param monthlyQuota   本周期 token 配额
 * @param usedTokens     本周期已用 token
 * @param remainingQuota 剩余配额。<b>由服务端算好下发</b>——
 *                       让前端做 {@code quota - used} 会把「究竟减不减得动」
 *                       变成一个各处各写一遍的隐式约定，且负数处理容易不一致
 * @param status         active / suspended / arrears
 */
public record BillingAccountVO(
        String planCode,
        BigDecimal balance,
        Long monthlyQuota,
        Long usedTokens,
        Long remainingQuota,
        String status) {
}
