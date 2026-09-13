package com.duduke.erp.entity.vo;

/**
 * 结束事件。
 * <p>
 * 携带 {@code conversationId} 是冗余兜底：新建会话时会话标识已在
 * HTTP 响应头 {@code X-Conversation-Id} 返回，但前端若因代理层改写丢了这个头，
 * 还能从本事件恢复。以「新建会话」场景为首要覆盖目标。
 *
 * @param conversationId 会话标识
 * @param messageId      助手消息落库后的主键，前端据此拉取引用与统计
 * @param status         completed / cancelled / failed
 * @param elapsedMs      本轮耗时
 * @param totalTokens    本轮 token 总量
 */
public record StreamDone(
        String conversationId,
        Long messageId,
        String status,
        long elapsedMs,
        Integer totalTokens) {
}
