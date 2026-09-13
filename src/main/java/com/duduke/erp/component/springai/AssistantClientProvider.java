package com.duduke.erp.component.springai;

import com.duduke.erp.config.ChatProperties;

import lombok.RequiredArgsConstructor;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * 对话模型客户端提供者。
 * <p>
 * <b>本项目是单模型</b>：Spring AI 的 OpenAI starter 已按
 * {@code spring.ai.openai.*} 自动装配好 {@code ChatClient.Builder}，
 * 这里只做一层薄封装，不重复造模型装配。
 * <p>
 * 之所以不复刻参考实现的 {@code ModelRegistry} + {@code AssistantClientProvider}：
 * 那套机制解决的是「多个 provider（DeepSeek / Qwen / Gemini）各自一套 starter、
 * base-url 不同、需要按 provider 缓存客户端」的问题。本项目只有一套
 * OpenAI 兼容配置，多模型路由是无用复杂度。
 * 后续若要加模型，从这里扩展即可——调用方只依赖本类，不需要改编排代码。
 */
@Component
@RequiredArgsConstructor
public class AssistantClientProvider {

    private final ChatClient.Builder chatClientBuilder;

    private final ChatProperties chatProperties;

    /**
     * 构建一个不带全局 Advisor 的 ChatClient。
     * <p>
     * <b>刻意不加 {@code defaultAdvisors}</b>：记忆与 RAG 都是「按请求决定挂不挂」的，
     * 挂成默认会污染所有调用（例如纯检索自查时不该写记忆）。
     * 调用方通过 {@code .advisors(...)} 按需装配。
     *
     * @return ChatClient 实例
     */
    public ChatClient client() {
        return this.chatClientBuilder.build();
    }

    /**
     * 取本轮回答的 token 上限。
     *
     * @return 上限值，0 表示不限制
     */
    public int maxOutputTokens() {
        return this.chatProperties.getMaxOutputTokens();
    }

}
