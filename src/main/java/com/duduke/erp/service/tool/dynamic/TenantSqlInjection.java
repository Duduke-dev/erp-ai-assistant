package com.duduke.erp.service.tool.dynamic;

/**
 * 租户条件注入结果。
 *
 * @param sql                  注入 {@code ent_code = ?} 后的 SQL
 * @param tenantParameterIndex 租户实参应插入的下标。
 *                             注入的条件插在 <b>插入点之前所有 {@code ?} 的后面</b>，
 *                             而插入点是 ORDER BY / GROUP BY / LIMIT 的起点，
 *                             所以下标 = 插入点之前的 {@code ?} 个数。
 *                             不能简单把租户值追加到实参末尾——原 SQL 若有
 *                             尾部子句里的参数，顺序就会错位。
 */
public record TenantSqlInjection(String sql, int tenantParameterIndex) {
}
