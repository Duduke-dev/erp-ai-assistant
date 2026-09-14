package com.duduke.erp.entity.dto;

/**
 * Tool 调用日志查询条件。
 * <p>
 * {@code tool_call_log} <b>有 ent_code 且不在 ignore-tables 中</b>，
 * 因此这里的查询会被 MP 租户插件自动加上 ent_code 条件——
 * 管理端只能看到<b>本租户</b>的调用流水，无需也不应该手写租户条件。
 */
public record ToolCallLogQueryDTO(
        /** 按 Tool 名精确匹配 */
        String toolName,

        /** success / error */
        String status,

        /** code / database，用于区分代码 Tool 与动态 Tool */
        String toolSource,

        /** 按某一轮问答归组 */
        String traceId,

        Integer pageNo,

        Integer pageSize) {
}
