package com.duduke.erp.entity.dto;

/**
 * 产品分页查询条件。
 * <p>
 * {@code keyword} 同时匹配产品编码与名称，避免前端为两个字段分别传参。
 */
public record ProductQueryDTO(
        String keyword,
        String category,
        String status,
        Integer pageNo,
        Integer pageSize) {
}
