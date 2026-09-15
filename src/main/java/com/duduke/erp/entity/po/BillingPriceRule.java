package com.duduke.erp.entity.po;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 模型计费单价（<b>平台级全局配置</b>）。
 * <p>
 * 没有 {@code ent_code}，已在 {@code app.tenant.ignore-tables} 中。
 * <p>
 * 单价带 {@code effectiveDate} 而<b>不是就地覆盖</b>：历史账期必须能按当时的价格重算。
 * 直接改单价会让已出账的月份对不上，而账目一旦不一致就很难解释。
 * 查询时取「不晚于目标日期」的最新一条。
 */
@Data
@TableName("billing_price_rule")
public class BillingPriceRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String modelName;

    /** 每千 token 输入单价 */
    private BigDecimal inputPrice;

    /** 每千 token 输出单价 */
    private BigDecimal outputPrice;

    /** 生效日期；同模型可有多个版本 */
    private LocalDate effectiveDate;

    private LocalDateTime createdAt;

}
