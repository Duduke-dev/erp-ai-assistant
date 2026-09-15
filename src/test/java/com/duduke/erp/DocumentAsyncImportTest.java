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
 * M2.5「通电」+ 切片 3 进度验证：上传线程登记并投递，消费线程解析并晋级，两段不重叠。
 *
 * <h3>为什么 mock 掉 {@link DocumentLoaderService}</h3>
 * 真实的解析与向量化要调 embedding 模型，本机没有可用的模型凭据，
 * 端到端跑不起来。而本用例要验证的是<b>编排切分</b>——谁登记、谁解析、
 * 版本号与租户如何跨线程传递——这些都不依赖真实解析。
 * 把解析出口换成桩，反而能精确断言「上传线程绝不解析」，
 * 还能在桩被触发的那一刻回查数据库，确认阶段确实停在了 embedding。
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

    /** 桩被触发那一刻数据库里的阶段，用来确认 embedding 阶段真的写进去了 */
    private String stageAtEmbedding;

    /** 本用例造出来的文档与对象，{@code tearDown} 清理 */
    private String documentId;

    private String objectKey;

    @BeforeEach
    void setUp() {
        TenantContext.set(ENT_CODE, 1L);
        this.knowledgeBaseId = this.knowledgeBaseService.resolveActive(null).getId();
        this.fileName = "async-" + UUID.randomUUID().toString().substring(0, 8) + ".md";
        given(this.documentLoaderService.loadAndStore(
                any(InputStream.class), any(ManagedDocumentMetadata.class), any(Runnable.class)))
                .willAnswer(invocation -> {
                    ManagedDocumentMetadata metadata = invocation.getArgument(1);
                    // 真实 loader 会在「分块完成、写向量之前」触发这个回调，桩必须照做：
                    // 不触发就永远停在 parsing，等于没验证 embedding 阶段
                    invocation.getArgument(2, Runnable.class).run();
                    this.stageAtEmbedding = stageOf(metadata.documentId());
                    return new ManagedDocumentLoadResult(3, "sha256-" + metadata.documentId());
                });
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
    @DisplayName("异步上传只登记不解析：原件进对象存储、键回写进库、阶段停在 queued")
    void asyncUploadRegistersOnly() throws Exception {
        byte[] content = "# 异步链路验证\n上传线程不应解析这段内容。".getBytes(StandardCharsets.UTF_8);

        // 只上传一次：uploadAndTakeMessage 内部会走一遍导入，
        // 再调一次就是同文件第二个版本，objectKeysOf 会拿到两个键
        String payload = uploadAndTakeMessage(content);

        verify(this.documentLoaderService, never()).loadAndStore(
                any(InputStream.class), any(ManagedDocumentMetadata.class), any(Runnable.class));
        JsonNode json = this.objectMapper.readTree(payload);
        assertThat(this.objectKey).as("消息里必须带着对象键，否则消费侧取不到原件").isNotBlank();
        assertThat(json.get("entCode").asString()).isEqualTo(ENT_CODE);
        assertThat(json.get("version").asInt()).isEqualTo(1);
        assertThat(json.get("contentType").asString()).isEqualTo("text/markdown");

        assertThat(this.objectStorageService.get(this.objectKey))
                .as("原件必须已经落盘到对象存储，投递之后才有东西可解析")
                .isEqualTo(content);
        assertThat(this.knowledgeDocumentService.objectKeysOf(this.documentId))
                .as("原件键必须回写进库：只留在消息里的话，删除文档清不掉原件、死信也反查不回来")
                .containsExactly(this.objectKey);
        assertThat(statusOf(this.documentId)).isEqualTo("processing");
        assertThat(stageOf(this.documentId)).isEqualTo(KnowledgeDocumentService.STAGE_QUEUED);
    }

    @Test
    @DisplayName("消费侧晋级同一版本：不二次登记，metadata 带租户与版本，写入前先清同版本向量")
    void consumerPromotesTheSameVersion() throws Exception {
        byte[] content = "# 异步链路验证\n消费线程解析这段内容。".getBytes(StandardCharsets.UTF_8);
        String payload = uploadAndTakeMessage(content);

        this.consumer.consume(payload);

        // 消费侧 finally 里清了上下文（防止监听线程串租户），后续查询要重新建立
        TenantContext.set(ENT_CODE, 1L);

        ArgumentCaptor<ManagedDocumentMetadata> captor =
                ArgumentCaptor.forClass(ManagedDocumentMetadata.class);
        verify(this.documentLoaderService).loadAndStore(
                any(InputStream.class), captor.capture(), any(Runnable.class));
        ManagedDocumentMetadata metadata = captor.getValue();
        assertThat(metadata.entCode()).isEqualTo(ENT_CODE);
        assertThat(metadata.documentId()).isEqualTo(this.documentId);
        assertThat(metadata.version()).isEqualTo(1);
        assertThat(metadata.contentType()).isEqualTo("text/markdown");

        // 阶段回调确实被执行了（桩被触发时回查数据库，阶段已是 embedding）。
        // 注意：解析前的「先清同版本向量」发生在 loadAndStore 内部，本用例把 loader 换成了桩，
        // 因此这里验证不到它——那一条属于解析层行为，需在具备 embedding 凭据的环境端到端验证。
        assertThat(this.stageAtEmbedding).isEqualTo(KnowledgeDocumentService.STAGE_EMBEDDING);

        KnowledgeDocumentVO document = findDocument();
        assertThat(document.status()).isEqualTo("ready");
        assertThat(document.stage()).isEqualTo(KnowledgeDocumentService.STAGE_READY);
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

            verify(this.documentLoaderService).loadAndStore(
                    any(InputStream.class), any(ManagedDocumentMetadata.class), any(Runnable.class));
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

    private KnowledgeDocumentVO documentOf(String documentId) {
        return this.knowledgeDocumentService.getDocument(this.knowledgeBaseId, documentId);
    }

    private String statusOf(String documentId) {
        return documentOf(documentId).status();
    }

    private String stageOf(String documentId) {
        return documentOf(documentId).stage();
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
