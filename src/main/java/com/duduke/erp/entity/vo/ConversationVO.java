package com.duduke.erp.entity.vo;

import java.time.LocalDateTime;

/**
 * 会话展示对象。
 *
 * @param conversationId 会话标识
 * @param title          标题（由首条提问生成）
 * @param modelId        使用的模型
 * @param messageCount   消息条数
 * @param totalTokens    累计 token
 * @param createdAt      创建时间
 * @param updatedAt      最后更新时间
 */
public record ConversationVO(
        String conversationId,
        String title,
        String modelId,
        Integer messageCount,
        Integer totalTokens,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
