package com.duduke.erp.entity.vo;

import java.time.LocalDateTime;

/**
 * 解析死信展示对象。
 *
 * <h3>刻意不含 {@code payload}</h3>
 * 列表只需要让运维判断「要不要重投」，文档名 + 版本 + 失败原因已经够；
 * 完整消息体留在表里和日志里，需要深挖时再查。
 */
public record DocumentParseDeadLetterVO(
        Long id,
        String documentId,
        Long knowledgeBaseId,
        Integer version,
        String fileName,
        String objectKey,
        String errorMessage,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
