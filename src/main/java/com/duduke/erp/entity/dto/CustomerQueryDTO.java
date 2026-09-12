package com.duduke.erp.entity.dto;

/**
 * 客户分页查询条件。
 * <p>
 * {@code keyword} 同时匹配客户编码与名称。
 */
public record CustomerQueryDTO(
        String keyword,
        String region,
        String status,
        Integer pageNo,
        Integer pageSize) {
}
