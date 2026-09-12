package com.duduke.erp.entity.dto;

/**
 * 库存分页查询条件。
 * <p>
 * {@code lowStock} 为 true 时只返回结存低于安全库存的记录（预警场景）。
 */
public record InventoryQueryDTO(
        String keyword,
        String warehouse,
        Boolean lowStock,
        Integer pageNo,
        Integer pageSize) {
}
