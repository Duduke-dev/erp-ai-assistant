package com.duduke.erp.service.tool.dynamic;

import java.util.List;

/**
 * SQL 模板绑定结果：已把 {@code :name} 占位符替换成 JDBC {@code ?}，并按键出现顺序收集实参。
 * <p>
 * 参数值只走 JDBC 绑定，<b>永远不拼进 SQL 文本</b>——这是动态 Tool 防注入的根。
 *
 * @param sql       替换后的 SQL
 * @param arguments 与 {@code ?} 一一对应的实参
 */
public record BoundSql(String sql, List<Object> arguments) {
}
