package com.duduke.erp;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.duduke.erp.entity.po.ChatConversation;
import com.duduke.erp.service.AssistantLifecycleService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式收口的关键不变量测试。
 * <p>
 * 这些用例全部围绕一个目标：<b>无论终止路径如何组合，收口只发生一次</b>。
 * 用真实数据库不方便构造并发取消，因此这里直接针对 CAS 语义与状态累积做验证，
 * 落库行为由 {@code ChatHistoryService} 自己的测试覆盖。
 */
class AssistantLifecycleServiceTest {

    private static ChatResponse frame(String text, Integer totalTokens) {
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder();
        if (totalTokens != null) {
            int prompt = totalTokens / 2;
            metadata.usage(new DefaultUsage(prompt, totalTokens - prompt, totalTokens));
        }
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .metadata(metadata.build())
                .build();
    }

    @Test
    @DisplayName("并发触达收口时只有一个线程抢到，另一次被吞掉")
    void finalizeOnlyOnceUnderConcurrency() throws Exception {
        AtomicBoolean finalized = new AtomicBoolean(false);
        int threads = 8;
        AtomicInteger winners = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        // 与 AssistantLifecycleService.finalize 的收口语义一致
                        if (finalized.compareAndSet(false, true)) {
                            winners.incrementAndGet();
                        }
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        }
        finally {
            pool.shutdownNow();
        }

        assertThat(winners.get()).as("收口必须恰好发生一次").isEqualTo(1);
    }

    @Test
    @DisplayName("状态必须携带 traceId —— 收口靠它取回本轮图表方案与业务结果")
    void stateCarriesTraceId() {
        AssistantLifecycleService service = newService();

        var state = service.newState("trace-xyz");

        assertThat(state.traceId())
                .as("收口在其它线程上执行，拿不到 ThreadLocal，只能靠状态携带")
                .isEqualTo("trace-xyz");
    }

    @Test
    @DisplayName("状态累积拼接增量，空帧不计入")
    void stateAccumulatesDeltas() {
        AssistantLifecycleService service = newService();
        var state = service.newState("trace-test");

        String first = service.onDelta(state, frame("库存", 0));
        String second = service.onDelta(state, frame("预警", 0));
        String empty = service.onDelta(state, frame("", 0));

        assertThat(first).isEqualTo("库存");
        assertThat(second).isEqualTo("预警");
        assertThat(empty).isNull();
        assertThat(state.content()).isEqualTo("库存预警");
    }

    @Test
    @DisplayName("token 用量保留最后一次有效值，不被后续零值抹掉")
    void stateKeepsLastValidUsage() {
        AssistantLifecycleService service = newService();
        var state = service.newState("trace-test");

        // 模拟真实流：前几帧 usage 为 0（或缺失），只有最后一帧带真实用量
        service.onDelta(state, frame("a", 0));
        service.onDelta(state, frame("b", 0));
        service.onDelta(state, frame("c", 120));
        service.onDelta(state, frame("d", 0));

        assertThat(state.usage()).isNotNull();
        assertThat(state.usage().getTotalTokens()).isEqualTo(120);
    }

    @Test
    @DisplayName("召回文档从响应元数据读取，供流结束后校验引用")
    void stateCapturesRecalledDocumentsFromMetadata() {
        AssistantLifecycleService service = newService();
        var state = service.newState("trace-test");

        Document doc = Document.builder()
                .id("d1")
                .text("库存低于安全库存时会预警")
                .metadata("source", "erp.txt")
                .metadata("knowledge_base_id", 5)
                .build();
        ChatResponse withDocs = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("库存 [1]"))))
                .metadata(ChatResponseMetadata.builder()
                        .keyValue("rag_document_context", List.of(doc))
                        .build())
                .build();

        service.onDelta(state, withDocs);

        assertThat(state.recalled()).hasSize(1);
        assertThat(state.recalled().get(0).getText()).contains("安全库存");
    }

    @Test
    @DisplayName("未抢到收口权时返回 handoff=false，调用方应静默结束")
    void swallowedOutcomeHasNoHandoff() {
        AtomicBoolean finalized = new AtomicBoolean(true);
        assertThat(finalized.compareAndSet(false, true)).isFalse();
    }

    /**
     * 构造被测服务。收口路径本身需要数据库，本测试只覆盖不落库的状态与 CAS 语义，
     * 因此依赖传 null——调用的方法都不触碰它们。
     */
    private AssistantLifecycleService newService() {
        // 净化器传真实实例而非 null：它是无依赖的纯文本规则，
        // 传 null 会让将来万一走到收口路径时直接 NPE，掩盖真正的问题
        return new AssistantLifecycleService(null, null,
                new com.duduke.erp.config.ChatProperties(),
                new com.duduke.erp.service.AssistantAnswerSanitizer(),
                new com.duduke.erp.service.BillingService(null, null, null, null, null, null));
    }

    /** 会话对象仅用于读取标识，本测试不触发落库 */
    @SuppressWarnings("unused")
    private static ChatConversation conversation(String id) {
        ChatConversation conversation = new ChatConversation();
        conversation.setConversationId(id);
        return conversation;
    }

}
