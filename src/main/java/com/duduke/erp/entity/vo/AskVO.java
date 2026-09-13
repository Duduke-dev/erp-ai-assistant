package com.duduke.erp.entity.vo;

import java.util.List;

/**
 * 对话回答。
 *
 * @param conversationId  会话标识（新建会话时回传，前端据此续聊）
 * @param messageId       本轮助手消息主键
 * @param answer          回答正文
 * @param mode            实际生效的模式
 * @param citations       本轮引用证据，未挂 RAG 或无命中时为空列表
 * @param ragDocCount     本轮通过资格过滤的召回分片数
 * @param promptTokens    输入 token
 * @param completionTokens 输出 token
 * @param totalTokens     合计 token
 * @param elapsedMs       本轮耗时（毫秒）
 */
public record AskVO(
        String conversationId,
        Long messageId,
        String answer,
        String mode,
        List<RagCitation> citations,
        Integer ragDocCount,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Long elapsedMs) {
}
