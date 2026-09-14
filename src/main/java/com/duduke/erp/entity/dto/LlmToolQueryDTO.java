package com.duduke.erp.entity.dto;

/**
 * 动态 Tool 分页查询条件。
 * <p>
 * {@code llm_tool} 是<b>全局配置表</b>（无 ent_code，已加入 ignore-tables），
 * 所以这里不需要租户条件——所有租户看到的是同一份 Tool 定义，
 * 隔离发生在执行时注入 ent_code，不在这张表上。
 */
public record LlmToolQueryDTO(
        /** 按 Tool 名称或说明模糊匹配 */
        String keyword,

        /** active / inactive，为空则不过滤 */
        String status,

        Integer pageNo,

        Integer pageSize) {
}
