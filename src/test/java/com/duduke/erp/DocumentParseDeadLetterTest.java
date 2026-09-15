package com.duduke.erp;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.duduke.erp.config.MessagingProperties;
import com.duduke.erp.entity.dto.DocumentImportRegistration;
import com.duduke.erp.entity.dto.DocumentParseMessage;
import com.duduke.erp.entity.dto.ManagedDocumentLoadResult;
import com.duduke.erp.entity.dto.ManagedDocumentMetadata;
import com.duduke.erp.entity.vo.DocumentParseDeadLetterVO;
import com.duduke.erp.entity.vo.KnowledgeDocumentVO;
import com.duduke.erp.service.DocumentLoaderService;
import com.duduke.erp.service.DocumentParseDeadLetterService;
import com.duduke.erp.service.KnowledgeBaseService;
import com.duduke.erp.service.KnowledgeDocumentIngestionService;
import com.duduke.erp.service.KnowledgeDocumentService;
import com.duduke.erp.service.ObjectStorageService;
import com.duduke.erp.tenant.TenantContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 死信链路验证（直连本机 RabbitMQ，非 mock）。
 *
 * <h3>为什么只启动死信容器</h3>
 * 全局 {@code auto-startup=false} 停掉所有监听器，再按 id 单独启动死信容器。
 * 这样既能验证「消息进 DLQ → 自动落库」，又不会让主队列监听器把重投的消息
 * 抢去真实解析（那需要 embedding 凭据，本机没有）。
 *
 * <h3>为什么还要 mock 掉 {@link DocumentLoaderService}</h3>
 * 兜底：万一主队列容器被别的路径启动，解析也不会真的去调模型。
 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class DocumentParseDeadLetterTest {

    private static final String ENT_CODE = "DEMO";

    private static final String DLQ_LISTENER_ID = "documentParseDlqListener";

    @Autowired
    private DocumentParseDeadLetterService deadLetterService;

    @Autowired
    private KnowledgeDocumentIngestionService ingestionService;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @Autowired
    private ObjectStorageService objectStorageService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitListenerEndpointRegistry registry;

    @Autowired
    private MessagingProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DocumentLoaderService documentLoaderService;

    private Long knowledgeBaseId;

    private String fileName;

    private String documentId;

    private String objectKey;

    private final List<Long> deadLetterIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TenantContext.set(ENT_CODE, 1L);
        this.knowledgeBaseId = this.knowledgeBaseService.resolveActive(null).getId();
        this.fileName = "dlq-" + UUID.randomUUID().toString().substring(0, 8) + ".md";
        given(this.documentLoaderService.loadAndStore(
                any(InputStream.class), any(ManagedDocumentMetadata.class), any(Runnable.class)))
                .willReturn(new ManagedDocumentLoadResult(2, "sha256-test"));
        this.registry.getListenerContainer(DLQ_LISTENER_ID).start();
        drainQueues();
    }

    @AfterEach
    void tearDown() {
        try {
            this.registry.getListenerContainer(DLQ_LISTENER_ID).stop();
            for (Long id : this.deadLetterIds) {
                this.deadLetterService.deleteForCleanup(id);
            }
            if (this.documentId != null) {
                this.ingestionService.deleteDocument(this.knowledgeBaseId, this.documentId);
            }
            if (this.objectKey != null) {
                this.objectStorageService.remove(this.objectKey);
            }
            drainQueues();
        }
        finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("消息进入死信队列后被自动登记：列表可见、状态 pending")
    void deadLetterIsRecordedWhenMessageLandsInDlq() throws Exception {
        DocumentParseMessage message = prepareDocument();

        this.rabbitTemplate.convertAndSend(
                this.properties.getDocumentExchange(),
                this.properties.getDocumentParseDlq(),
                this.objectMapper.writeValueAsString(message));

        DocumentParseDeadLetterVO recorded = awaitDeadLetter();
        this.deadLetterIds.add(recorded.id());
        assertThat(recorded.documentId()).isEqualTo(this.documentId);
        assertThat(recorded.version()).isEqualTo(1);
        assertThat(recorded.objectKey()).isNotBlank();
        assertThat(recorded.status()).isEqualTo(DocumentParseDeadLetterService.STATUS_PENDING);
    }

    @Test
    @DisplayName("重投：文档版本回到 processing 且原始消息体原样回到主队列")
    void retryResetsDocumentAndRepublishes() throws Exception {
        DocumentParseMessage message = prepareDocument();
        failCurrentVersion();
        Long id = recordDeadLetter(message);

        this.deadLetterService.retry(id);

        KnowledgeDocumentVO document = this.knowledgeDocumentService
                .getDocument(this.knowledgeBaseId, this.documentId);
        assertThat(document.status())
                .as("不重置回 processing 的话，markReady 会拒绝，重投必然再次失败")
                .isEqualTo(KnowledgeDocumentService.STATUS_PROCESSING);
        assertThat(document.stage()).isEqualTo(KnowledgeDocumentService.STAGE_QUEUED);

        Object republished = null;
        for (int i = 0; i < 10 && republished == null; i++) {
            republished = this.rabbitTemplate.receiveAndConvert(properties.getDocumentParseQueue());
            if (republished == null) {
                Thread.sleep(200);
            }
        }
        assertThat(republished).as("重投后主队列应重新收到消息").isNotNull();
        assertThat(this.objectMapper.readTree(republished.toString()).get("documentId").asString())
                .isEqualTo(this.documentId);

        assertThat(findDeadLetter(id).status()).isEqualTo(DocumentParseDeadLetterService.STATUS_RETRIED);
    }

    @Test
    @DisplayName("丢弃后不可再处置，记录本身保留")
    void discardedDeadLetterCannotBeHandledTwice() throws Exception {
        DocumentParseMessage message = prepareDocument();
        Long id = recordDeadLetter(message);

        this.deadLetterService.discard(id);
        assertThat(findDeadLetter(id).status()).isEqualTo(DocumentParseDeadLetterService.STATUS_DISCARDED);

        assertThatThrownBy(() -> this.deadLetterService.discard(id))
                .as("已处置的死信再点一次应当明确报错，而不是静默无事发生")
                .hasMessageContaining("待处置");
        assertThatThrownBy(() -> this.deadLetterService.retry(id))
                .hasMessageContaining("待处置");
    }

    /** 走一遍异步上传，拿到文档与消息（此时版本仍是 processing） */
    private DocumentParseMessage prepareDocument() throws Exception {
        byte[] content = "# 死信验证\n".getBytes(StandardCharsets.UTF_8);
        this.ingestionService.importDocument(this.knowledgeBaseId, this.fileName,
                "text/markdown", content, null);
        // 上传会往主队列投一条，取走避免污染后续断言
        drainMainQueue();
        this.documentId = this.knowledgeDocumentService.listDocuments(this.knowledgeBaseId).stream()
                .filter(doc -> this.fileName.equals(doc.title()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到文档：" + this.fileName))
                .documentId();
        this.objectKey = this.knowledgeDocumentService.objectKeysOf(this.documentId).stream()
                .findFirst().orElse(null);
        return new DocumentParseMessage(ENT_CODE, this.documentId, this.knowledgeBaseId,
                this.objectKey, this.fileName, "text/markdown", 1);
    }

    private void failCurrentVersion() {
        this.knowledgeDocumentService.markFailed(
                new DocumentImportRegistration(this.documentId, this.knowledgeBaseId, this.fileName, 1),
                "解析失败（测试构造）");
    }

    private Long recordDeadLetter(DocumentParseMessage message) throws Exception {
        this.deadLetterService.record(message, this.objectMapper.writeValueAsString(message), "测试构造");
        DocumentParseDeadLetterVO created = findByDocumentId();
        this.deadLetterIds.add(created.id());
        return created.id();
    }

    private DocumentParseDeadLetterVO findByDocumentId() {
        return this.deadLetterService.list().stream()
                .filter(item -> this.documentId.equals(item.documentId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("死信未登记：" + this.documentId));
    }

    private DocumentParseDeadLetterVO findDeadLetter(Long id) {
        return this.deadLetterService.list().stream()
                .filter(item -> id.equals(item.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("死信不存在：" + id));
    }

    private DocumentParseDeadLetterVO awaitDeadLetter() throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            List<DocumentParseDeadLetterVO> matched = this.deadLetterService.list().stream()
                    .filter(item -> this.documentId != null && this.documentId.equals(item.documentId()))
                    .toList();
            if (!matched.isEmpty()) {
                return matched.get(0);
            }
            Thread.sleep(200);
        }
        throw new AssertionError("死信未在预期时间内落库：" + this.documentId);
    }

    private void drainMainQueue() {
        while (this.rabbitTemplate.receiveAndConvert(this.properties.getDocumentParseQueue()) != null) {
            // 读到 null 说明队列已空
        }
    }

    private void drainQueues() {
        drainMainQueue();
        while (this.rabbitTemplate.receiveAndConvert(this.properties.getDocumentParseDlq()) != null) {
            // 同上
        }
    }

}
