package com.duduke.erp.entity.vo;

import java.time.LocalDateTime;

/**
 * 知识文档展示对象。
 * <p>
 * {@code requiresReindex} 在「状态为 ready 但向量由旧模型生成」时为 true——
 * 这类文档检索时会被 {@code embedding_model} 条件过滤掉，需要重新导入才能恢复可检索。
 */
public record KnowledgeDocumentVO(
        Long id,
        Long knowledgeBaseId,
        String documentId,
        String title,
        Integer version,
        String status,
        String stage,
        Long fileSize,
        String contentType,
        Integer chunkCount,
        String embeddingModel,
        Boolean requiresReindex,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
