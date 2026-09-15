package com.duduke.erp.service;

import java.io.ByteArrayInputStream;

import com.duduke.erp.common.exception.BusinessException;
import com.duduke.erp.entity.dto.DocumentParseMessage;
import com.duduke.erp.entity.dto.ManagedDocumentMetadata;
import com.duduke.erp.tenant.TenantContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 文档解析消费者：取原始件 → 解析 → 分块 → 向量化 → 回写状态。
 *
 * <h3>最关键的一点：消费线程没有租户上下文</h3>
 * 监听线程不是处理 HTTP 请求的线程，ThreadLocal 里<b>没有租户信息</b>。
 * 而下游的 {@link KnowledgeDocumentIngestionService#parseAndPromote} 要写文档状态与向量库，
 * 都要靠租户标识。因此进入业务逻辑前，必须用消息里携带的 {@code entCode} 显式建立上下文；
 * 退出时（成功、失败都要）清理，因为监听线程是复用的，残留会给下一条消息串租户。
 *
 * <h3>为什么调 {@code parseAndPromote} 而不是 {@code importFile}</h3>
 * 版本登记（{@code beginImport}）在上传线程已经做过，版本号随消息带出。
 * 这里再走一次 {@code importFile} 会触发第二次登记 —— 同一个文件多出一个版本，
 * 能编译、能跑、结果是错的。消费侧只负责「解析 + 晋级」这后半段。
 *
 * <h3>失败策略</h3>
 * 异常一律往外抛：由 Spring AMQP 的重试与死信把消息转到死信队列，
 * <b>不在这里吞掉</b>。吞掉会得到一个"上传成功但永远检索不到"的文档，
 * 而且没有任何痕迹——这比失败更难查。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentParseConsumer {

    private final ObjectStorageService objectStorageService;

    private final KnowledgeDocumentIngestionService ingestionService;

    private final ObjectMapper objectMapper;

    /**
     * 消费一条解析任务。
     *
     * @param payload JSON 消息体（投递方序列化后的字符串）
     */
    // id 显式命名：运维/测试可按 id 单独启停这个容器，不必动全局开关
    @RabbitListener(queues = "${app.mq.document-parse-queue}", id = "documentParseListener")
    public void consume(String payload) {
        DocumentParseMessage message = parse(payload);
        log.info("开始消费解析任务：documentId={}, entCode={}",
                message.documentId(), message.entCode());

        try {
            // 必须先建立上下文：下游写文档状态与写向量库都要靠它
            TenantContext.set(message.entCode(), null);

            byte[] content = this.objectStorageService.get(message.objectKey());
            if (content == null || content.length == 0) {
                throw new BusinessException("对象存储中找不到原件：" + message.objectKey());
            }

            // 用 ByteArrayInputStream：内存流没有需要释放的资源，
            // 套 try-with-resources 反而要处理 close() 的受检 IOException
            ManagedDocumentMetadata metadata = new ManagedDocumentMetadata(
                    message.entCode(), message.knowledgeBaseId(), message.documentId(),
                    message.version(), message.fileName(), message.contentType());
            this.ingestionService.parseAndPromote(metadata, new ByteArrayInputStream(content));
            log.info("解析任务消费完成：documentId={}, version={}",
                    message.documentId(), message.version());
        }
        catch (RuntimeException e) {
            // 继续抛出：让重试/死信接手，不要静默吞掉。
            // 解析失败时的文档状态由 KnowledgeDocumentIngestionService#parseAndPromote 内部标记
            // （它 catch 后走 completeFailedImport）；本处代劳不了——
            // 标记失败要按「文档 + 版本」定位，registration 由 metadata 派生，只在那一侧成立。
            throw e;
        }
        finally {
            // 监听线程复用，不清理会让下一条消息读到本条消息的租户
            TenantContext.clear();
        }
    }

    private DocumentParseMessage parse(String payload) {
        try {
            return this.objectMapper.readValue(payload, DocumentParseMessage.class);
        }
        catch (RuntimeException e) {
            // 坏消息不应反复重投：抛出的异常会让它进死信队列等待人工处理
            throw new BusinessException("解析任务消息无法反序列化：" + payload);
        }
    }

}
