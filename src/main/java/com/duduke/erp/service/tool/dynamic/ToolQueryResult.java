package com.duduke.erp.service.tool.dynamic;

import java.util.List;
import java.util.Map;

/**
 * 动态 Tool 的查询结果。
 *
 * @param rows 结果行，key 为列名（PG 返回小写 snake_case）
 */
public record ToolQueryResult(List<Map<String, Object>> rows) {
}
