package com.duduke.erp.entity.po;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 账户交易流水（充值 / 扣费 / 赠送）。
 * <p>
 * 有 {@code ent_code}，租户条件由 MP 插件注入。
 * <p>
 * 记流水而不只改余额，是因为<b>余额是状态、流水才是证据</b>：
 * 只维护余额时，用户问「这 12 块钱是怎么扣的」无从回答，
 * 对不上账也无从追溯。
 */
@Data
@TableName("billing_transaction")
public class BillingTransaction {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String entCode;

    /** 交易单号，租户内唯一 */
    private String transactionNo;

    /** recharge / deduction / gift */
    private String type;

    /** 变动金额，扣费为负 */
    private BigDecimal amount;

    /** 变动后余额，便于对账时逐笔还原 */
    private BigDecimal balanceAfter;

    /** 本次涉及 token 数 */
    private Long tokens;

    private String remark;

    private LocalDateTime createdAt;

}
