package com.duduke.erp.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.duduke.erp.entity.dto.DocumentParseMessage;
import com.duduke.erp.tenant.TenantContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 死信队列监听：把消费失败的任务登记进 {@code document_parse_dead_letter}。
 *
 * <h3>反序列化失败的消息不落库</h3>
 * 落库需要 {@code ent_code} 才能归属租户，而它只能从消息里取。
 * 一条连 JSON 都解析不出来的消息既无法归属也无法重投，
 * 落库只会造出一堆没人认领、也没法处理的孤儿行——
 * 这种情况只记 error 日志，让它在队列里留着供人工到控制台查。
 *
 * <h3>监听线程同样没有租户上下文</h3>
 * 与主队列消费者同理：进入业务逻辑前用消息里的 {@code entCode} 建立上下文，
 * 退出时清理，否则线程复用会串租户。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentParseDeadLetterListener {

    private final DocumentParseDeadLetterService deadLetterService;

    private final ObjectMapper objectMapper;

    @RabbitListener(queues = "${app.mq.document-parse-dlq}", id = "documentParseDlqListener")
    public void consume(Message message) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        DocumentParseMessage parsed = tryParse(payload);
        if (parsed == null) {
            log.error("死信消息无法反序列化，跳过登记（无法归属租户也无法重投）：{}", payload);
            return;
        }

        try {
            TenantContext.set(parsed.entCode(), null);
            this.deadLetterService.record(parsed, payload, deathReason(message));
        }
        catch (RuntimeException e) {
            // 登记失败必须抛出：这条消息会被重投或留在队列里。
            // 静默吞掉会让死信"消失"——用户看到文档 failed，运维在列表里却什么也查不到。
            throw e;
        }
        finally {
            TenantContext.clear();
        }
    }

    private DocumentParseMessage tryParse(String payload) {
        try {
            return this.objectMapper.readValue(payload, DocumentParseMessage.class);
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 从 {@code x-death} 头里取失败原因。
     * <p>
     * 消息体里只有任务信息，没有失败原因——原因在 RabbitMQ 加的头里。
     * 不取就只能登记一条"不知道为什么失败"的死信，等于没登记。
     */
    private static String deathReason(Message message) {
        Object death = message.getMessageProperties().getHeaders().get("x-death");
        if (death instanceof List<?> deaths && !deaths.isEmpty()
                && deaths.get(0) instanceof Map<?, ?> first) {
            Object reason = first.get("reason");
            if (reason != null) {
                return reason.toString();
            }
        }
        return null;
    }

}
