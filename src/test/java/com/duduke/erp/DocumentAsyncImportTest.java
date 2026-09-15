package com.duduke.erp;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.duduke.erp.config.MessagingProperties;
import com.duduke.erp.entity.dto.ManagedDocumentLoadResult;
import com.duduke.erp.entity.dto.ManagedDocumentMetadata;
import com.duduke.erp.entity.vo.KnowledgeDocumentVO;
import com.duduke.erp.service.DocumentLoaderService;
import com.duduke.erp.service.DocumentParseConsumer;
import com.duduke.erp.service.KnowledgeBaseService;
import com.duduke.erp.service.KnowledgeDocumentIngestionService;
import com.duduke.erp.service.KnowledgeDocumentService;
import com.duduke.erp.service.ObjectStorageService;
import com.duduke.erp.tenant.TenantContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * M2.5「通电」验证：上传线程登记并投递，消费线程解析并晋级，两段不重叠。
 *
 * <h3>为什么 mock 掉 {@link DocumentLoaderService}</h3>
 * 真实的解析与向量化要调 embedding 模型，本机没有可用的模型凭据，
 * 端到端跑不起来。而本用例要验证的是<b>编排切分</b>——谁登记、谁解析、
 * 版本号与租户如何跨线程传递——这些都不依赖真实解析。
 * 把解析出口换成桩，反而能精确断言「上传线程绝不解析」。
 *
 * <h3>为什么要关掉监听器自启动</h3>
 * 与 {@code DocumentParsePublisherTest} 同理：监听器一挂就会真实消费，
 * 本用例想在断言完「投递后的状态」之后再手工触发消费，被抢跑就没得验了。
 *
 * <h3>关于同步降级用例里改 {@code asyncEnabled}</h3>
 * 该配置对象只属于本测试类的上下文（本类带独立 properties，不会与其它类共用），
 * 且改后在 {@code finally} 复位，不会污染其它用例。
 */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class DocumentAsyncImportTest {

    private static final String ENT_CODE = "DEMO";

    @Autowired
    private KnowledgeDocumentIngestionService ingestionService;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @Autowired
    private DocumentParseConsumer consumer;

    @Autowired
    private ObjectStorageService objectStorageService;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private MessagingProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DocumentLoaderService documentLoaderService;

    private Long knowledgeBaseId;

    private String fileName;

    /** 本用例造出来的文档与对象，{@code tearDown} 清理 */
    private String documentId;

    private String objectKey;

    @BeforeEach
    void setUp() {
        TenantContext.set(ENT_CODE, 1L);
        this.knowledgeBaseId = this.knowledgeBaseService.resolveActive(null).getId();
        this.fileName = "async-" + UUID.randomUUID().toString().substring(0, 8) + ".md";
        given(this.documentLoaderService.loadAndStore(any(InputStream.class), any(ManagedDocumentMetadata.class)))
                .willReturn(new ManagedDocumentLoadResult(3, "sha256-" + this.fileName));
        drainQueue();
    }

    @AfterEach
    void tearDown() {
        try {
            if (this.documentId != null) {
                this.ingestionService.deleteDocument(this.knowledgeBaseId, this.documentId);
            }
            if (this.objectKey != null) {
                this.objectStorageService.remove(this.objectKey);
            }
            drainQueue();
        }
        finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("异步上传只登记不解析：原件进对象存储、消息进队列、版本停在 processing")
    void asyncUploadRegistersOnly() throws Exception {
        byte[] content = "# 异步链路验证\n上传线程不应解析这段内容。".getBytes(StandardCharsets.UTF_8);

        this.ingestionService.importDocument(this.knowledgeBaseId, this.fileName,
                "text/markdown", content, null);

        verify(this.documentLoaderService, never())
                .loadAndStore(any(InputStream.class), any(ManagedDocumentMetadata.class));

        String payload = uploadAndTakeMessage(content);
        JsonNode json = this.objectMapper.readTree(payload);
        assertThat(this.objectKey).as("消息里必须带着对象键，否则消费侧取不到原件").isNotBlank();
        assertThat(json.get("entCode").asString()).isEqualTo(ENT_CODE);
        assertThat(json.get("version").asInt()).isEqualTo(1);
        assertThat(json.get("contentType").asString()).isEqualTo("text/markdown");

        assertThat(this.objectStorageService.get(this.objectKey))
                .as("原件必须已经落盘到对象存储，投递之后才有东西可解析")
                .isEqualTo(content);
        assertThat(statusOf(this.documentId)).isEqualTo("processing");
    }

    @Test
    @DisplayName("消费侧晋级同一版本：不二次登记，metadata 带租户编码与真实版本号")
    void consumerPromotesTheSameVersion() throws Exception {
        byte[] content = "# 异步链路验证\n消费线程解析这段内容。".getBytes(StandardCharsets.UTF_8);
        String payload = uploadAndTakeMessage(content);

        this.consumer.consume(payload);

        // 消费侧 finally 里清了上下文（防止监听线程串租户），后续查询要重新建立
        TenantContext.set(ENT_CODE, 1L);

        ArgumentCaptor<ManagedDocumentMetadata> captor =
                ArgumentCaptor.forClass(ManagedDocumentMetadata.class);
        verify(this.documentLoaderService)
                .loadAndStore(any(InputStream.class), captor.capture());
        ManagedDocumentMetadata metadata = captor.getValue();
        assertThat(metadata.entCode()).isEqualTo(ENT_CODE);
        assertThat(metadata.documentId()).isEqualTo(this.documentId);
        assertThat(metadata.version()).isEqualTo(1);
        assertThat(metadata.contentType()).isEqualTo("text/markdown");

        KnowledgeDocumentVO document = findDocument();
        assertThat(document.status()).isEqualTo("ready");
        assertThat(document.version())
                .as("消费侧若再走一次 beginImport，这里就会变成 2 —— 同一文件平白多一个版本")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("async-enabled 关闭时回退同步：上传线程内直接解析完成")
    void fallsBackToSyncWhenAsyncDisabled() {
        byte[] content = "# 同步降级验证".getBytes(StandardCharsets.UTF_8);
        this.properties.setAsyncEnabled(false);
        try {
            this.ingestionService.importDocument(this.knowledgeBaseId, this.fileName,
                    "text/markdown", content, null);

            verify(this.documentLoaderService)
                    .loadAndStore(any(InputStream.class), any(ManagedDocumentMetadata.class));
            this.documentId = findDocument().documentId();
            assertThat(statusOf(this.documentId)).isEqualTo("ready");
        }
        finally {
            this.properties.setAsyncEnabled(true);
        }
    }

    /** 上传并取回消息体；顺带记下 documentId / objectKey 供清理 */
    private String uploadAndTakeMessage(byte[] content) throws Exception {
        this.ingestionService.importDocument(this.knowledgeBaseId, this.fileName,
                "text/markdown", content, null);
        String payload = null;
        for (int i = 0; i < 10 && payload == null; i++) {
            Object received = this.rabbitTemplate.receiveAndConvert(properties.getDocumentParseQueue());
            payload = received == null ? null : received.toString();
            if (payload == null) {
                Thread.sleep(200);
            }
        }
        assertThat(payload).as("异步开启时必须有消息投递出去").isNotNull();
        JsonNode json = this.objectMapper.readTree(payload);
        this.documentId = json.get("documentId").asString();
        this.objectKey = json.get("objectKey").asString();
        return payload;
    }

    private String statusOf(String documentId) {
        return this.knowledgeDocumentService.listDocuments(this.knowledgeBaseId).stream()
                .filter(doc -> documentId.equals(doc.documentId()))
                .map(KnowledgeDocumentVO::status)
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到文档：" + documentId));
    }

    private KnowledgeDocumentVO findDocument() {
        return this.knowledgeDocumentService.listDocuments(this.knowledgeBaseId).stream()
                .filter(doc -> this.fileName.equals(doc.title()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到文档：" + this.fileName));
    }

    /** 清空队列：残留消息会被下一个用例误取 */
    private void drainQueue() {
        while (this.rabbitTemplate.receiveAndConvert(this.properties.getDocumentParseQueue()) != null) {
            // 读到 null 说明队列已空
        }
    }

}
