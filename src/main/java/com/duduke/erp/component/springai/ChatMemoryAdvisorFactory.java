package com.duduke.erp.component.springai;

import com.duduke.erp.config.ChatProperties;

import lombok.RequiredArgsConstructor;

import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.stereotype.Component;

/**
 * 记忆 Advisor 装配。
 * <p>
 * 用 {@link MessageWindowChatMemory} 限定窗口大小，底层仓库是本项目的
 * {@link JdbcChatMemoryRepository}（读 {@code chat_message} 表）。
 * <p>
 * <b>为什么不做成全局 {@code @Bean} 直接挂进 ChatClient 的 defaultAdvisors</b>：
 * 记忆是「按请求决定挂不挂」的——纯检索自查、内部摘要这类调用不该写记忆，
 * 也不该读到会话历史。做成请求级 Advisor 由编排层按需装配，边界更清楚。
 */
@Component
@RequiredArgsConstructor
public class ChatMemoryAdvisorFactory {

    private final JdbcChatMemoryRepository chatMemoryRepository;

    private final ChatProperties chatProperties;

    /**
     * 构造记忆 Advisor。每轮问答构造一次成本极低（仅是个不可变对象），
     * 但底层 ChatMemory 是共享的，窗口裁剪状态不会因此丢失。
     */
    public MessageChatMemoryAdvisor create() {
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(this.chatMemoryRepository)
                .maxMessages(this.chatProperties.getMemoryWindowSize())
                .build();
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }

}
