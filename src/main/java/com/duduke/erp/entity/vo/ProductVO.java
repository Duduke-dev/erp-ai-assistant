package com.duduke.erp.entity.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 产品展示对象。
 */
public record ProductVO(
        Long id,
        String productCode,
        String productName,
        String spec,
        String unit,
        String category,
        BigDecimal safetyStock,
        BigDecimal unitPrice,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
