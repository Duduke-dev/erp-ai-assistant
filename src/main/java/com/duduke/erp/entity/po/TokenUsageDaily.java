package com.duduke.erp.entity.po;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 按天 × 模型维度的 token 用量。
 * <p>
 * 唯一键是 {@code (ent_code, usage_date, model_name)}：
 * 同一天同一模型只有一行，累加更新。<b>model_name 允许为空</b>，
 * 为空表示「未按模型细分」的汇总行——PostgreSQL 的唯一索引对 NULL 不去重，
 * 因此写入时不要把模型名写成空串，否则会与 NULL 行并存出两行汇总。
 */
@Data
@TableName("token_usage_daily")
public class TokenUsageDaily {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String entCode;

    private LocalDate usageDate;

    private String modelName;

    private Long promptTokens;

    private Long completionTokens;

    private Long totalTokens;

    private Integer requestCount;

    private LocalDateTime updatedAt;

}
