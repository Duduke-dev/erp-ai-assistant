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

    /** 单条消息最大重试次数，超过进死信队列 */
    private int maxAttempts = 3;

}
