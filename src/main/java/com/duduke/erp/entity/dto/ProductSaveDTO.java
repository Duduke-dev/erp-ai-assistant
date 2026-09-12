package com.duduke.erp.entity.dto;

import java.math.BigDecimal;

/**
 * 产品新增 / 更新入参。
 * <p>
 * 新增与更新共用同一结构：新增走 {@code POST /products}，更新走 {@code PUT /products/{id}}，
 * 主键来自 URL 路径，因此这里不带 id 字段。
 */
public record ProductSaveDTO(
        String productCode,
        String productName,
        String spec,
        String unit,
        String category,
        BigDecimal safetyStock,
        BigDecimal unitPrice,
        String status) {
}
