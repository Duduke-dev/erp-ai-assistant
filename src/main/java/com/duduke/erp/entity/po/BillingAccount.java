package com.duduke.erp.entity.po;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 租户计费账户。
 * <p>
 * <b>有 {@code ent_code} 且不在 ignore-tables 中</b>：租户条件由 MP 插件自动注入，
 * 这里不写也不该写租户条件。
 * <p>
 * 与套餐表 {@code billing_plan} 的关系是「账户持有套餐的快照」：
 * 账户上同时存了 {@code planCode} 与 {@code monthlyQuota}。
 * 冗余配额是刻意的——套餐改了价或改了额度，<b>不应追溯影响已签约账户的当前周期额度</b>，
 * 否则用户会在月中突然发现配额变了。
 */
@Data
@TableName("billing_account")
public class BillingAccount {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String entCode;

    /** 当前套餐编码，指向 billing_plan.plan_code */
    private String planCode;

    /** 账户余额，金额一律用 BigDecimal */
    private BigDecimal balance;

    /** 本周期配额（开户时从套餐快照而来） */
    private Long monthlyQuota;

    /** 本周期已用 token */
    private Long usedTokens;

    /** active / suspended / arrears */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
