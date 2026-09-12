package com.duduke.erp.entity.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 销售订单新增 / 编辑入参（含明细行）。
 * <p>
 * 订单总金额同样由 Service 依据明细汇总，不接受前端传入。
 */
/**
 * 销售订单新增 / 更新入参（含明细行）。
 * <p>
 * 新增走 {@code POST /sales_orders}，更新走 {@code PUT /sales_orders/{id}}，
 * 主键来自 URL 路径，因此这里不带 id 字段。
 */
public record SalesOrderSaveDTO(
        String orderNo,
        Long customerId,
        LocalDate orderDate,
        LocalDate deliveryDate,
        String status,
        String remark,
        List<SalesOrderItemSaveDTO> items) {
}
