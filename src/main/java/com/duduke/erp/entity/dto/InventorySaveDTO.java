package com.duduke.erp.entity.dto;

import java.math.BigDecimal;

/**
 * 库存新增 / 编辑入参。
 * <p>
 * 本表记录「产品 + 仓库 + 批次」的当前结存；出入库历史在 stock_movement，
 * M1 阶段不在此维护流水，只提供结存的增删改查。
 */
/**
 * 库存新增 / 更新入参。
 * <p>
 * 新增走 {@code POST /inventories}，更新走 {@code PUT /inventories/{id}}，
 * 主键来自 URL 路径，因此这里不带 id 字段。
 */
public record InventorySaveDTO(
        Long productId,
        String productName,
        String warehouse,
        String location,
        String batchNo,
        BigDecimal quantity,
        BigDecimal safetyStock) {
}
