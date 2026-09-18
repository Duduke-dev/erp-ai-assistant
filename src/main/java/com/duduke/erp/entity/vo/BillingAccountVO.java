package com.duduke.erp.entity.vo;

import java.math.BigDecimal;

/**
 * 计费账户展示对象。
 *
 * @param id             账户主键。<b>必须下发</b>——充值（{@code POST /accounts/{id}/recharges}）
 *                       与改套餐（{@code PUT /accounts/{id}}）都以 id 定位，
 *                       不下发 id 等于前端拿不到任何可用标识，这两个操作只能停在纸上
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
        Long id,
        String planCode,
        BigDecimal balance,
        Long monthlyQuota,
        Long usedTokens,
        Long remainingQuota,
        String status) {
}
