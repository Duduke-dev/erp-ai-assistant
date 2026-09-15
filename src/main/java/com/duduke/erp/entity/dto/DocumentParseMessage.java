package com.duduke.erp.entity.dto;

/**
 * 文档解析任务消息体。
 *
 * <h3>{@code entCode} 必须随消息携带</h3>
 * 消费跑在 RabbitMQ 的监听线程上，那个线程<b>没有租户上下文</b>
 * （ThreadLocal 只在处理 HTTP 请求的线程上有值）。而消费过程要：
 * 写 {@code knowledge_document} 状态、写向量库（按 {@code ent_code} 过滤）、
 * 记日志——全都要租户标识。
 * <p>
 * 不放在消息里就只剩两条路，都不好：
 * <ul>
 *   <li>消费侧反查文档表拿 {@code ent_code}——但查库本身就需要租户上下文，鸡生蛋；</li>
 *   <li>绕过租户插件直查——那等于把隔离打开，风险远大于省一个字段。</li>
 * </ul>
 * 因此由投递方（HTTP 线程，上下文健全）把 {@code entCode} 写进消息，
 * 消费方在进入业务逻辑前用它显式建立上下文。
 *
 * @param entCode         租户编码，消费侧据此建立上下文
 * @param documentId      知识文档主键
 * @param knowledgeBaseId 所属知识库
 * @param objectKey       原件在对象存储中的键，消费侧据此下载
 * @param fileName        原始文件名，用于判断解析格式
 */
public record DocumentParseMessage(
        String entCode,
        Long documentId,
        Long knowledgeBaseId,
        String objectKey,
        String fileName) {
}
