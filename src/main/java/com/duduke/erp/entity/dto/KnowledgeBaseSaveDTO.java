package com.duduke.erp.entity.dto;

/**
 * 知识库新增 / 更新入参。
 */
public record KnowledgeBaseSaveDTO(
        String name,
        String description,
        Boolean isDefault) {
}
