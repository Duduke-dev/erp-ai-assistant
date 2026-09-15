package com.duduke.erp.entity.dto;

/**
 * 租户新增 / 更新入参。
 * <p>
 * <b>{@code entCode} 只在新增时生效</b>：它是租户的身份，一行数据、
 * 所有业务表、向量库元数据都以它为隔离键。改编码等于换了一个租户，
 * 原有数据会全部「找不到」，因此更新时忽略该字段。
 */
public record TenantSaveDTO(
        String entCode,
        String entName,
        String status) {
}
