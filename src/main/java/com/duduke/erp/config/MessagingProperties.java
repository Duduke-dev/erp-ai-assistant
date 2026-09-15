package com.duduke.erp.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 消息队列（RabbitMQ）配置。
 *
 * <h3>为什么队列与交换机名都放进配置</h3>
 * 队列名一旦硬编码在多处，改一次就要全项目搜替换，漏一处就是「消息投到一个没人消费的队列」——
 * 这种故障不报错，只表现为文档永远停在 processing。
 */
@Data
@ConfigurationProperties(prefix = "app.mq")
public class MessagingProperties {

    /** 文档解析队列（主队列） */
    private String documentParseQueue = "erp.document.parse";

    /** 文档解析死信队列 */
    private String documentParseDlq = "erp.document.parse.dlq";

    /** 文档相关交换机 */
    private String documentExchange = "erp.document";

    /** 解析任务路由键 */
    private String documentParseRoutingKey = "document.parse";

    /**
     * 是否启用异步解析。
     * <p>
     * 留这个开关是为了<b>可降级</b>：消息队列不可用时能切回同步解析，
     * 而不是让「上传」这个功能整体不可用。
     */
    private boolean asyncEnabled = true;

    // 这里刻意不提供「重试次数」：
    // 消费失败一律直接进死信队列，由管理端决定是否重投。
    // 理由：解析失败绝大多数是确定性的（文件损坏、格式不支持、模型不可用），
    // 自动重试只是把同一个错误跑三遍，还会在日志里堆出三倍噪声。
    // 真需要重试的场景（网络抖动）由人工在死信列表点一次「重投」即可，
    // 且那次重投是幂等的（解析前会先清同版本向量）。
    // 早先这里有个 maxAttempts 字段，但 yml 并未接 spring.rabbitmq.listener.simple.retry，
    // 等于配了个不起作用的值——比没有更危险，故删除。

}
