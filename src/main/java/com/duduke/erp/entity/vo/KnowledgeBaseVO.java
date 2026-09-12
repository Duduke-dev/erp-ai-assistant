package com.duduke.erp.entity.vo;

import java.time.LocalDateTime;

/**
 * 知识库展示对象。
 * <p>
 * {@code documentCount} 只统计 ready 版本，与检索实际可命中的范围一致。
 */
public record KnowledgeBaseVO(
        Long id,
        String name,
        String description,
        Boolean isDefault,
        String status,
        Long documentCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
