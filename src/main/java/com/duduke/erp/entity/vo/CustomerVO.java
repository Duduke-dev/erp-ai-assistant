package com.duduke.erp.entity.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 客户展示对象。
 */
public record CustomerVO(
        Long id,
        String customerCode,
        String customerName,
        String contactPerson,
        String contactPhone,
        String region,
        BigDecimal creditLimit,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
