package com.duduke.erp.component.springai;

import com.duduke.erp.config.ChatProperties;
import com.duduke.erp.config.RagProperties;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 集成配置。
 * <p>
 * 嵌入模型、Chat 模型与向量库都由 starter 自动装配，这里只负责把
 * RAG 与对话参数绑定为可注入对象。
 */
@Configuration
@EnableConfigurationProperties({RagProperties.class, ChatProperties.class})
public class SpringAiConfig {
}
