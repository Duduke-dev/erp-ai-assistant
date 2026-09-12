package com.duduke.erp.entity.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存展示对象。
 * <p>
 * {@code warning} 由 Service 计算：结存低于安全库存时为 true，
 * 便于前端直接据此渲染预警标记，不必自己比较数值。
 */
public record InventoryVO(
        Long id,
        Long productId,
        String productName,
        String warehouse,
        String location,
        String batchNo,
        BigDecimal quantity,
        BigDecimal safetyStock,
        Boolean warning,
        LocalDateTime updatedAt) {
}
