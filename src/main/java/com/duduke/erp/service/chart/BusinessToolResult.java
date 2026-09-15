package com.duduke.erp.service.chart;

import java.util.List;
import java.util.Map;

/**
 * 一次业务 Tool 调用返回的结构化结果。
 * <p>
 * 只保留「够画图」的最小信息：Tool 名 + 行列表。列名与类型都在这里，
 * 后端据此做字段绑定与聚合——<b>模型完全不接触这些 schema</b>。
 * <p>
 * 行用 {@code Map<String, Object>} 而非实体：Tool 查询本就返回
 * {@code List<Map<String,Object>>}，转成实体既没必要（列随 Tool 变化）
 * 又会丢失动态 Tool 的任意列。
 */
public record BusinessToolResult(String toolName, List<Map<String, Object>> rows) {
}
