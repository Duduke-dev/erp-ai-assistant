package com.duduke.erp.service;

import com.duduke.erp.common.exception.BusinessException;
import com.duduke.erp.config.MessagingProperties;
import com.duduke.erp.entity.dto.DocumentParseMessage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 文档解析任务投递器。
 * <p>
 * 刻意把消息体序列化成 <b>JSON 字符串</b>再发（而不是直接发 POJO）：
 * 项目用的是 Jackson 3（{@code tools.jackson}），而 Spring AMQP 的
 * {@code Jackson2JsonMessageConverter} 绑定 Jackson 2，注册它会引入第二套 Jackson。
 * 自己序列化依赖面最小，也避免"两端类型映射不一致"这种隐性问题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentParsePublisher {

    private final RabbitTemplate rabbitTemplate;

    private final ObjectMapper objectMapper;

    private final MessagingProperties properties;

    /**
     * 投递一条解析任务。
     *
     * @throws BusinessException 投递失败时抛出——<b>不能静默吞掉</b>：
     *                           消息没投出去，而文档状态已经写成 processing 的话，
     *                           这条文档就永远停在那里，且没有任何报错
     */
    public void publish(DocumentParseMessage message) {
        if (message == null || message.documentId() == null) {
            throw new BusinessException("解析任务消息不完整");
        }
        if (!properties.isAsyncEnabled()) {
            log.debug("异步解析已关闭，跳过投递：documentId={}", message.documentId());
            return;
        }
        try {
            String payload = this.objectMapper.writeValueAsString(message);
            this.rabbitTemplate.convertAndSend(
                    properties.getDocumentExchange(),
                    properties.getDocumentParseRoutingKey(),
                    payload);
            log.debug("解析任务已投递：documentId={}, queue={}",
                    message.documentId(), properties.getDocumentParseQueue());
        }
        catch (RuntimeException e) {
            // AmqpException 是 RuntimeException 的子类，一并覆盖，无需再单列
            throw new BusinessException(500, "解析任务投递失败：" + e.getMessage(), e);
        }
    }

}
