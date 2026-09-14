package com.duduke.erp.entity.vo;

import java.time.LocalDateTime;

/**
 * 动态 Tool 展示对象。
 * <p>
 * 与实体字段一一对应但不含任何持久化注解——VO 只负责出参形状，
 * 实体形状变化时 VO 可以保持稳定，前端不受内部重构影响。
 */
public record LlmToolVO(
        Long id,
        String toolName,
        String toolDesc,
        String inputSchema,
        String sqlTemplate,
        String tableAlias,
        Integer resultLimit,
        String status,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
