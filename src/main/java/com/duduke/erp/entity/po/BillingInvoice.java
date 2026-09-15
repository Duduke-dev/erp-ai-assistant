package com.duduke.erp.entity.po;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 账期发票。
 * <p>
 * 唯一键 {@code (ent_code, period)}：一个租户一个账期只应有一张发票。
 * 重复开票会导致对账时不知道以哪张为准，因此服务端在开票前先查、有则拒绝。
 */
@Data
@TableName("billing_invoice")
public class BillingInvoice {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String entCode;

    /** 账期，yyyy-MM */
    private String period;

    private Long totalTokens;

    private BigDecimal totalAmount;

    private LocalDateTime createdAt;

}
