package com.duduke.erp.entity.vo;

import java.util.List;

/**
 * 对话回答。
 *
 * @param conversationId  会话标识（新建会话时回传，前端据此续聊）
 * @param messageId       本轮助手消息主键
 * @param answer          回答正文（Markdown，由前端 MarkdownViewer 渲染）
 * @param mode            实际生效的模式
 * @param citations       本轮引用证据，未挂 RAG 或无命中时为空列表
 * @param ragDocCount     本轮可用于引用的证据片段数——<b>与 citations 同源</b>。
 *                        含两条来源：knowledge 模式的 Advisor 召回、auto 模式的知识检索 Tool 召回
 *                        （见 RagRecallRecorder）。字段名沿用历史命名，语义已比原先更宽。
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
