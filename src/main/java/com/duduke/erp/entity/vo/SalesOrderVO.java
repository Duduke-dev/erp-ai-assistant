package com.duduke.erp.entity.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 销售订单展示对象（含明细行）。
 * <p>
 * 列表查询时明细同样一并返回：订单行数有限（通常不超过几十行），
 * 省去前端为每行再发一次请求。
 */
public record SalesOrderVO(
        Long id,
        String orderNo,
        Long customerId,
        String customerName,
        LocalDate orderDate,
        LocalDate deliveryDate,
        BigDecimal totalAmount,
        String status,
        String remark,
        List<SalesOrderItemVO> items,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
