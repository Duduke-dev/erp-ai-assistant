package com.duduke.erp.component.rabbitmq;

import com.duduke.erp.config.MessagingProperties;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑声明：交换机、主队列、死信队列、绑定。
 *
 * <h3>为什么用 DirectExchange 而不是默认交换机</h3>
 * 默认交换机只能按队列名点对点投递，一旦以后要加「解析完成后再扇出给索引/通知」
 * 这类消费者，就得改投递代码。提前用交换机 + 路由键，扩展时只加绑定。
 *
 * <h3>消息体用 JSON 字符串，不注册 Jackson 转换器</h3>
 * Boot 4 走的是 Jackson 3（{@code tools.jackson}），而 Spring AMQP 的
 * {@code Jackson2JsonMessageConverter} 绑定的是 Jackson 2。
 * 混用会引入第二套 Jackson 或直接不可用。因此这里<b>不注册转换器</b>，
 * 由投递方自己序列化成 JSON 字符串、消费方自己解析——
 * 依赖面最小，也不会有"类型映射在两端不一致"的隐性问题。
 */
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class RabbitMqConfig {

    /**
     * 文档解析主队列。
     * <p>
     * 绑死信交换机与路由键：消费失败超过重试上限后，消息转投死信队列而不是被丢弃或无限重投。
     * 不配死信的话，一条坏消息会把队列堵住——这是 MQ 里最典型的"静默停摆"。
     */
    @Bean
    public Queue documentParseQueue(MessagingProperties properties) {
        return QueueBuilder.durable(properties.getDocumentParseQueue())
                .deadLetterExchange(properties.getDocumentExchange())
                .deadLetterRoutingKey(properties.getDocumentParseDlq())
                .build();
    }

    /** 死信队列。不设 TTL：留着人工排查，需要时手动重投 */
    @Bean
    public Queue documentParseDlq(MessagingProperties properties) {
        return QueueBuilder.durable(properties.getDocumentParseDlq()).build();
    }

    @Bean
    public DirectExchange documentExchange(MessagingProperties properties) {
        return new DirectExchange(properties.getDocumentExchange(), true, false);
    }

    /** 解析任务：路由键 → 主队列 */
    @Bean
    public Binding documentParseBinding(Queue documentParseQueue, DirectExchange documentExchange,
                                        MessagingProperties properties) {
        return BindingBuilder.bind(documentParseQueue)
                .to(documentExchange)
                .with(properties.getDocumentParseRoutingKey());
    }

    /** 死信路由：同一个交换机，用死信队列名当路由键 */
    @Bean
    public Binding documentParseDlqBinding(Queue documentParseDlq, DirectExchange documentExchange,
                                           MessagingProperties properties) {
        return BindingBuilder.bind(documentParseDlq)
                .to(documentExchange)
                .with(properties.getDocumentParseDlq());
    }

}
