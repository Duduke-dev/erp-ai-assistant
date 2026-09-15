package com.duduke.erp;

import com.duduke.erp.config.MessagingProperties;
import com.duduke.erp.entity.dto.DocumentParseMessage;
import com.duduke.erp.service.DocumentParsePublisher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 解析任务投递的真实联通验证（直连本机 RabbitMQ，非 mock）。
 * <p>
 * 验证投递侧三件事：消息真的进了队列、JSON 结构可被消费方解析、
 * 以及 <b>entCode 确实随消息带出</b>——它是消费线程建立租户上下文的唯一来源，
 * 丢了这一项，消费侧要么查不到数据，要么被迫绕过租户隔离。
 * <p>
 * 本用例只覆盖投递侧；消费侧（含跨线程租户上下文）尚未实现。
 */
/**
 * 解析任务投递的真实联通验证（直连本机 RabbitMQ，非 mock）。
 * <p>
 * <b>为什么要关掉监听器自启动</b>：消费侧的 {@code DocumentParseConsumer} 一挂
 * {@code @RabbitListener}，上下文启动就会真实消费队列。那样本用例发出去的消息
 * 会被消费者抢先取走，{@code receiveAndConvert} 拿到 null —— 表现为"投递没生效"，
 * 实则是被消费了。这里用测试级属性停用自启动，让投递侧可被单独验证。
 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class DocumentParsePublisherTest {

    @Autowired
    private DocumentParsePublisher publisher;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private MessagingProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Queue documentParseQueue;

    @Test
    @DisplayName("投递后消息进入队列，且 JSON 含租户编码（消费侧建上下文的唯一来源）")
    void publishLandsInQueueWithTenantCode() throws Exception {
        DocumentParseMessage message = new DocumentParseMessage(
                "DEMO", 999_001L, 1L, "DEMO/abc/手册.pdf", "手册.pdf");

        this.publisher.publish(message);

        Object raw = receiveWithRetry();
        assertThat(raw)
                .as("投递后应能在队列中取到消息；取不到说明交换机/路由键/队列绑定有误")
                .isNotNull();

        JsonNode json = this.objectMapper.readTree(raw.toString());
        assertThat(json.get("entCode").asString()).isEqualTo("DEMO");
        assertThat(json.get("documentId").asLong()).isEqualTo(999_001L);
        assertThat(json.get("objectKey").asString()).isEqualTo("DEMO/abc/手册.pdf");
    }

    @Test
    @DisplayName("队列已声明为持久化，且绑定了死信路由（避免坏消息堵住队列）")
    void queueIsDurableWithDeadLetter() {
        assertThat(this.documentParseQueue.isDurable()).isTrue();
        assertThat(this.documentParseQueue.getArguments())
                .as("必须绑死信交换机，否则消费失败的消息会被无限重投或直接丢弃")
                .containsKey("x-dead-letter-exchange");
        assertThat(this.properties.getDocumentParseDlq()).isNotBlank();
    }

    /**
     * 非阻塞接收 + 短暂轮询：投递是同步的，但队列投递与可见之间可能有一瞬延迟，
     * 直接断言 null 会偶发失败。
     */
    private Object receiveWithRetry() throws InterruptedException {
        String queue = this.properties.getDocumentParseQueue();
        for (int i = 0; i < 10; i++) {
            Object received = this.rabbitTemplate.receiveAndConvert(queue);
            if (received != null) {
                return received;
            }
            Thread.sleep(200);
        }
        return null;
    }

}
