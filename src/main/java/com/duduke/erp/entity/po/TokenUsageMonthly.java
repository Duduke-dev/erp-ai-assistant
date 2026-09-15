package com.duduke.erp.entity.po;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 按月 × 模型维度的 token 用量。
 * <p>
 * 唯一键 {@code (ent_code, period, model_name)}。{@code period} 形如 {@code 2026-09}，
 * 用字符串而非日期：月度统计天然按「账期」聚合，账期不是某一天，
 * 用日期会引入「用哪天代表这个月」这种没有正确答案的问题。
 */
@Data
@TableName("token_usage_monthly")
public class TokenUsageMonthly {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String entCode;

    /** 账期，yyyy-MM */
    private String period;

    private String modelName;

    private Long totalTokens;

    private Integer requestCount;

    private LocalDateTime updatedAt;

}
