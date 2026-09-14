package com.duduke.erp.entity.vo;

import java.time.LocalDateTime;

/**
 * Tool 调用日志展示对象。
 * <p>
 * {@code arguments} 是模型实际传入的参数 JSON，排查「模型为什么这么调」时是关键证据，
 * 因此一并返回（它可能较长，但管理端是低频操作，不值得为此再开一个详情接口）。
 */
public record ToolCallLogVO(
        Long id,
        String conversationId,
        String traceId,
        String toolName,
        String toolSource,
        String modelName,
        String mode,
        String arguments,
        String status,
        Long elapsedMs,
        Integer resultCount,
        String errorSummary,
        LocalDateTime createdAt) {
}
