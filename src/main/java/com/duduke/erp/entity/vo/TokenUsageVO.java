package com.duduke.erp.entity.vo;

/**
 * 用量汇总展示对象（按天或按账期聚合后的单行）。
 *
 * @param bucket      时间桶：按天为 {@code yyyy-MM-dd}，按账期为 {@code yyyy-MM}
 * @param modelName   模型名；为「未按模型细分」的汇总时为空
 * @param totalTokens 合计 token
 * @param requestCount 请求次数
 */
public record TokenUsageVO(
        String bucket,
        String modelName,
        Long totalTokens,
        Integer requestCount) {
}
