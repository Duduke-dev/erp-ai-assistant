package com.duduke.erp.entity.dto;

import java.math.BigDecimal;

/**
 * 客户新增 / 更新入参。
 * <p>
 * 新增走 {@code POST /customers}，更新走 {@code PUT /customers/{id}}，
 * 主键来自 URL 路径，因此这里不带 id 字段。
 */
public record CustomerSaveDTO(
        String customerCode,
        String customerName,
        String contactPerson,
        String contactPhone,
        String region,
        BigDecimal creditLimit,
        String status) {
}
