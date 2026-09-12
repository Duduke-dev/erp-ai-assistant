package com.duduke.erp.entity.dto;

import java.time.LocalDate;

/**
 * 销售订单分页查询条件。
 * <p>
 * {@code dateFrom} / {@code dateTo} 按订单日期闭区间过滤，用于销售统计的明细钻取。
 */
public record SalesOrderQueryDTO(
        String keyword,
        Long customerId,
        String status,
        LocalDate dateFrom,
        LocalDate dateTo,
        Integer pageNo,
        Integer pageSize) {
}
