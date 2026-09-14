package com.duduke.erp.entity.dto;

/**
 * 动态 Tool 新增 / 更新入参。
 * <p>
 * 新增与更新共用同一结构：新增走 {@code POST /api/tools/llm_tools}，
 * 更新走 {@code PUT /api/tools/llm_tools/{id}}，主键来自 URL 路径，故这里不带 id。
 * <p>
 * <b>不在这里做校验</b>：SQL 模板、入参 schema、结果上限、别名的规则全部在
 * {@code SqlToolValidator} 里，管理端与「装载时」复用同一套，
 * 否则两边规则会慢慢漂移，出现「存得进去但注册不了」的哑弹。
 */
public record LlmToolSaveDTO(
        /** Tool 名称，全局唯一，下发给模型作为 function 名 */
        String toolName,

        /** 给模型看的说明，决定模型何时选它 */
        String toolDesc,

        /** 入参 JSON Schema 原文，原样下发给模型 */
        String inputSchema,

        /** 带 :name 命名参数的 SQL 模板 */
        String sqlTemplate,

        /** 主表别名，租户注入拼成 alias.ent_code；可为空 */
        String tableAlias,

        /** 单次返回行数上限，1 ~ 500 */
        Integer resultLimit,

        /** active / inactive */
        String status,

        String remark) {
}
