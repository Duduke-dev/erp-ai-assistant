package com.duduke.erp.entity.dto;

/**
 * 对话提问请求。
 *
 * @param conversationId 会话标识，为空时新建会话
 * @param question       提问内容
 * @param mode           模式：auto（默认，业务+知识）/ knowledge（纯知识库）
 * @param knowledgeBaseId 指定知识库，为空时用默认库；knowledge 模式生效
 */
public record AskDTO(
        String conversationId,
        String question,
        String mode,
        Long knowledgeBaseId) {
}
