package com.duduke.erp.entity.po;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 计费套餐（<b>平台级全局配置</b>）。
 * <p>
 * 没有 {@code ent_code}，且已在 {@code app.tenant.ignore-tables} 中——
 * 所有租户共用同一份套餐定义。若漏加忽略，查询会被注入 {@code ent_code}
 * 到一张没有该列的表上，直接报错。
 */
@Data
@TableName("billing_plan")
public class BillingPlan {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 套餐编码，全局唯一 */
    private String planCode;

    private String planName;

    /** 月度 token 配额 */
    private Long monthlyQuota;

    /** 套餐月费 */
    private BigDecimal price;

    private LocalDateTime createdAt;

}
