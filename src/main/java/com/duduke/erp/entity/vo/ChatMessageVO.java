package com.duduke.erp.entity.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话消息展示对象。
 *
 * @param id            消息主键
 * @param role          user / assistant
 * @param content       正文
 * @param mode          本轮模式
 * @param status        completed / cancelled / failed
 * @param errorMessage  失败原因摘要
 * @param citations     本轮引用证据
 * @param ragDocCount   本轮召回分片数
 * @param promptTokens  输入 token
 * @param completionTokens 输出 token
 * @param totalTokens   合计 token
 * @param elapsedMs     耗时（毫秒）
 * @param createdAt     创建时间
 */
public record ChatMessageVO(
        Long id,
        String role,
        String content,
        String mode,
        String status,
        String errorMessage,
        List<RagCitation> citations,
        Integer ragDocCount,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Long elapsedMs,
        LocalDateTime createdAt) {
}
