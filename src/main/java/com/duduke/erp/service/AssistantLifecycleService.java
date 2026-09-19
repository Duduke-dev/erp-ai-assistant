package com.duduke.erp.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.duduke.erp.config.ChatProperties;
import com.duduke.erp.entity.po.ChatConversation;
import com.duduke.erp.entity.po.ChatMessage;
import com.duduke.erp.entity.vo.RagCitation;
import com.duduke.erp.entity.vo.StreamCitations;
import com.duduke.erp.entity.vo.StreamDone;
import com.duduke.erp.entity.vo.StreamError;
import com.duduke.erp.tenant.TenantContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * 流式回答的收口。
 * <p>
 * 核心职责只有一个：<b>保证「落库助手消息」这件事在一轮流式里只发生一次</b>。
 *
 * <h3>为什么必须用 CAS</h3>
 * 三条终止路径会指向同一个收口动作，且它们<b>可能并发</b>：
 * <ul>
 *   <li>正常结束 → {@code onComplete}</li>
 *   <li>模型异常 → {@code onError}</li>
 *   <li>用户中断 → {@code onError} 里包装成 {@code IOException}，或 {@code onCompletion} 兜底</li>
 * </ul>
 * 用户点「停止生成」再立刻关页面，就足以让「取消」和「完成」两个回调先后进来。
 * 若不用 CAS 而用 {@code if (done)} 判断，两个线程可能同时通过检查，
 * 结果是**同一条回答落库两次、token 统计翻倍**。
 *
 * <h3>取消时也要落库</h3>
 * 用户中断不是错误。已生成的部分是用户看过的内容，必须存下来，
 * 否则刷新页面会发现这段回答凭空消失。状态记为 {@code cancelled}。
 *
 * <h3>为什么统计要用原子引用</h3>
 * token 用量与召回文档只在流进行中可观测，而收口发生在流之后。
 * 用一个「会一直记着最后有效值」的容器把它们带过边界，
 * 比让收口逻辑反向查询更简单，也不依赖上游是否保留了响应对象。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantLifecycleService {

    /** 助手回答的三种结局，与 {@code chat_message.status} 的取值一致 */
    private static final String STATUS_COMPLETED = ChatHistoryService.STATUS_COMPLETED;

    private static final String STATUS_CANCELLED = ChatHistoryService.STATUS_CANCELLED;

    private static final String STATUS_FAILED = ChatHistoryService.STATUS_FAILED;

    /**
     * 召回文档在 {@code ChatResponse} 元数据里的键，由
     * {@code RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT} 定义。
     */
    private static final String RAG_DOCUMENT_CONTEXT_KEY =
            org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT;

    private final ChatHistoryService chatHistoryService;

    private final RagCitationService ragCitationService;

    private final ChatProperties chatProperties;

    private final AssistantAnswerSanitizer answerSanitizer;

    private final BillingService billingService;

    /**
     * 一轮流式回答期间可观测到的运行时数据。
     * <p>
     * 由流进行中的回调持续写入，收口时读取一次。
     */
    public static final class StreamState {

        /** 已生成的文本。用 StringBuilder 而非不可变拼接，避免长回答的 O(n²) 拷贝 */
        private final StringBuilder content = new StringBuilder();

        /**
         * 本轮链路 ID。
         * <p>
         * 收口阶段要靠它取回本轮的图表方案与业务结果。收口可能落在
         * Reactor / SSE 容器线程上，因此必须在创建状态时就带上——
         * 不能指望收口时还能从 ThreadLocal 拿到。
         */
        private String traceId;

        /** token 用量。仅在 totalTokens > 0 时覆盖，保证留下最后一次有效值 */
        private final AtomicReference<Usage> usage = new AtomicReference<>();

        /** 本轮召回文档，用于回答结束后做引用校验 */
        private final AtomicReference<List<Document>> recalled = new AtomicReference<>(List.of());

        /**
         * 追加增量。空增量直接忽略——上游偶尔发空帧。
         */
        void append(String delta) {
            if (delta != null && !delta.isEmpty()) {
                this.content.append(delta);
            }
        }

        /**
         * 记录 token 用量。
         * <p>
         * 流式响应的用量<b>通常只在最后一帧出现</b>，前面若干帧的
         * {@code totalTokens} 是 0。若不做判断直接覆盖，最后会被某一帧的 0 抹掉。
         */
        void recordUsage(Usage value) {
            if (value != null && value.getTotalTokens() != null && value.getTotalTokens() > 0) {
                this.usage.set(value);
            }
        }

        void recordRecalled(List<Document> documents) {
            if (documents != null && !documents.isEmpty()) {
                this.recalled.set(documents);
            }
        }

        /** 当前已生成的文本，供取消时落库与结束时校验引用 */
        public String content() {
            return this.content.toString();
        }

        public Usage usage() {
            return this.usage.get();
        }

        public List<Document> recalled() {
            return this.recalled.get();
        }

        public String traceId() {
            return this.traceId;
        }

        void traceId(String value) {
            this.traceId = value;
        }

    }

    /**
     * 流式收口的完整结果，交给调用方决定下发哪几个事件。
     *
     * @param delta      需要补发的增量（已下发过的部分不重复发）
     * @param citations  引用事件，无引用时为 null
     * @param done       结束事件
     * @param error      错误事件，成功时为 null
     * @param handoff    是否已由本次调用完成收口（false 表示被其它路径抢先，调用方应静默结束）
     */
    public record StreamOutcome(
            String delta,
            StreamCitations citations,
            StreamDone done,
            StreamError error,
            boolean handoff) {

        /** 被其它终止路径抢先收口时的空结果 */
        static StreamOutcome swallowed() {
            return new StreamOutcome(null, null, null, null, false);
        }

    }

    /**
     * 为流式回答创建状态容器。
     * <p>
     * 必须在**启动流之前**创建：用户可能在第一个 token 到达前就点停止，
     * 那时若状态还没建好，这段（虽然为空的）回答就没地方落库。
     */
    public StreamState newState(String traceId) {
        StreamState state = new StreamState();
        state.traceId(traceId);
        return state;
    }

    /**
     * 记录一次增量，返回可直接下发的文本。
     * <p>
     * 由流的 {@code onNext} 调用。返回 null 表示空帧，调用方应跳过下发
     * （但状态里也不会记，空帧不参与内容累积）。
     */
    public String onDelta(StreamState state, ChatResponse response) {
        if (response == null) {
            return null;
        }
        state.recordUsage(response.getMetadata() == null ? null : response.getMetadata().getUsage());
        state.recordRecalled(recalledFrom(response));

        if (response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        String text = response.getResult().getOutput().getText();
        if (text == null || text.isEmpty()) {
            return null;
        }
        state.append(text);
        return text;
    }

    /**
     * 取本轮召回文档。
     * <p>
     * 优先读 {@code ChatResponse} 的元数据：{@code RetrievalAugmentationAdvisor.after()}
     * 会把文档写进 metadata（键 {@code rag_document_context}，见其字节码
     * {@code chatResponseBuilder.metadata("rag_document_context", docs)}）。
     * 流式下这是最可靠的取法——元数据随帧到达，与读取它的线程一致，
     * 不需要 ThreadLocal 跨线程可用。
     * <p>
     * 保留 {@link RagContextFormatter#recalledDocuments()} 兜底：上游若改变元数据传递方式，
     * 回退到「检索时暂存」仍可能拿到证据。失败模式是丢引用，不会拿到错误引用。
     */
    private List<Document> recalledFrom(ChatResponse response) {
        if (response.getMetadata() != null) {
            Object value = response.getMetadata().get(RAG_DOCUMENT_CONTEXT_KEY);
            if (value instanceof List<?> list && !list.isEmpty()) {
                return list.stream()
                        .filter(Document.class::isInstance)
                        .map(Document.class::cast)
                        .toList();
            }
        }
        return RagContextFormatter.recalledDocuments();
    }

    /**
     * 正常结束。
     */
    public StreamOutcome onComplete(StreamState state, ChatConversation conversation,
                                    String mode, long startedAt, AtomicBoolean finalized,
                                    int basePromptTokens, int baseCompletionTokens) {
        return finalize(state, conversation, mode, startedAt, finalized,
                STATUS_COMPLETED, null, basePromptTokens, baseCompletionTokens);
    }

    /**
     * 失败结束。
     *
     * @param error 原始异常，仅用于日志；对外只发稳定文案
     */
    public StreamOutcome onError(StreamState state, ChatConversation conversation,
                                 String mode, long startedAt, AtomicBoolean finalized,
                                 Throwable error, int basePromptTokens, int baseCompletionTokens) {
        log.warn("流式回答中断：conversationId={}, mode={}",
                conversation.getConversationId(), mode, error);
        return finalize(state, conversation, mode, startedAt, finalized,
                STATUS_FAILED, error == null ? "生成失败" : error.getMessage(),
                basePromptTokens, baseCompletionTokens);
    }

    /**
     * 用户中断。
     * <p>
     * 已生成内容照常落库，状态为 {@code cancelled}，<b>不下发任何终止事件</b>——
     * 连接已经断了，发也发不出去；前端自己知道是它断的。
     */
    public StreamOutcome onCancel(StreamState state, ChatConversation conversation,
                                  String mode, long startedAt, AtomicBoolean finalized,
                                  int basePromptTokens, int baseCompletionTokens) {
        log.info("用户中断流式回答：conversationId={}, 已生成 {} 字",
                conversation.getConversationId(), state.content().length());
        return finalize(state, conversation, mode, startedAt, finalized,
                STATUS_CANCELLED, null, basePromptTokens, baseCompletionTokens);
    }

    /**
     * 收口：落库助手消息 + 组装下发事件。
     * <p>
     * {@code finalized} 的 CAS 是整个类的核心——见类注释。
     * 未被本次调用抢到收口权时返回 {@link StreamOutcome#swallowed()}，
     * 调用方应静默结束，不能重复落库。
     */
    private StreamOutcome finalize(StreamState state, ChatConversation conversation,
                                   String mode, long startedAt, AtomicBoolean finalized,
                                   String status, String errorMessage,
                                   int basePromptTokens, int baseCompletionTokens) {
        if (!finalized.compareAndSet(false, true)) {
            // 已被其它终止路径收口（例如取消与完成并发），本次必须静默退出
            log.debug("流式收口已被抢先，跳过重复处理：conversationId={}",
                    conversation.getConversationId());
            return StreamOutcome.swallowed();
        }

        // 收口可能落在非请求线程上（Reactor / SSE 容器线程），显式恢复租户上下文，
        // 否则落库时会因缺少 ent_code 失败
        String entCode = conversation.getEntCode();
        Long userId = TenantContext.getUserId();
        boolean restored = false;
        if (entCode != null && TenantContext.getEntCode() == null) {
            TenantContext.set(entCode, userId);
            restored = true;
        }

        try {
            Usage usage = state.usage();
            int promptTokens = usage == null || usage.getPromptTokens() == null
                    ? basePromptTokens : usage.getPromptTokens();
            int completionTokens = usage == null || usage.getCompletionTokens() == null
                    ? baseCompletionTokens : usage.getCompletionTokens();
            long elapsedMs = System.currentTimeMillis() - startedAt;

            // 只在成功的轮次里校验引用：失败/取消时回答不完整，
            // 拿半截文本去提取编号会得到残缺引用，不如不发
            boolean usable = STATUS_COMPLETED.equals(status);
            // 净化只在成功轮次做：取消/失败时文本是半截的，
            // 此时删旁白可能把仅有的一点内容也删掉，宁可原样保留（status 字段已标明）。
            // 必须在引用校验之前净化——旁白里的编号不该被当成引用。
            String answer = usable
                    ? this.answerSanitizer.sanitize(state.content())
                    : state.content();
            List<RagCitation> citations = usable
                    ? this.ragCitationService.validate(answer, state.recalled())
                    : List.of();
            String citationsJson = encodeQuietly(citations);
            int ragDocCount = usable ? state.recalled().size() : 0;

            ChatMessage saved = this.chatHistoryService.saveAssistantMessage(
                    conversation, answer, mode,
                    promptTokens, completionTokens, promptTokens + completionTokens, elapsedMs,
                    status, errorMessage, ragDocCount, citationsJson);

            // 用量采集 + 扣费：旁路，BillingService 内部已吞异常。
            // 取消的轮次同样记录——已产生的 token 是真实消耗（totalTokens 为 0 时自动跳过）
            this.billingService.recordConsumption(conversation.getModelId(),
                    promptTokens, completionTokens, promptTokens + completionTokens);

            StreamDone done = new StreamDone(
                    conversation.getConversationId(), saved.getId(), status, elapsedMs,
                    promptTokens + completionTokens);

            if (STATUS_FAILED.equals(status)) {
                // errorMessage 就是 onError 传进来的原始异常摘要（落库用的是同一个值），
                // 可开关地一并下发给前端——默认关，见 ChatProperties#exposeErrorDetail
                return new StreamOutcome(null, null, null,
                        new StreamError("STREAM_ERROR", "回答生成失败，请稍后重试",
                                this.chatProperties.isExposeErrorDetail() ? errorMessage : null), true);
            }

            StreamCitations citationEvent = citations.isEmpty()
                    ? null
                    : new StreamCitations(resolveKnowledgeBaseId(state), ragDocCount, citations);

            return new StreamOutcome(null, citationEvent, done, null, true);
        }
        catch (RuntimeException e) {
            // 落库本身失败（如数据库不可用）：这已经是最外层，不能再抛，
            // 否则 SSE 连接会以未处理异常收场。降级为 error 事件。
            log.error("流式收口落库失败：conversationId={}", conversation.getConversationId(), e);
            return new StreamOutcome(null, null, null,
                    new StreamError("PERSIST_ERROR", "回答已生成但保存失败，请重试",
                            this.chatProperties.isExposeErrorDetail() ? rootMessage(e) : null), true);
        }
        finally {
            if (restored) {
                TenantContext.clear();
            }
            // 线程复用，必须清理，否则下一次请求会读到本轮证据导致引用串档
            RagContextFormatter.clearRecalledDocuments();
        }
    }

    /**
     * 取最内层异常的消息，供开发期的 error detail 使用。
     * <p>
     * 取根因而不是最外层：外层多是包装异常（{@code DataAccessException}、{@code CompletionException}），
     * 真正有诊断价值的信息——唯一约束冲突、连接被拒、字段不存在——都在最内层。
     */
    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    /**
     * 本轮知识库标识。取自召回文档的元数据——
     * 检索时已按 {@code knowledge_base_id} 过滤，所以整批必然同库。
     */
    private Long resolveKnowledgeBaseId(StreamState state) {
        return state.recalled().stream()
                .map(document -> document.getMetadata().get("knowledge_base_id"))
                .filter(java.util.Objects::nonNull)
                .map(value -> value instanceof Number number ? number.longValue() : null)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /** 引用编码失败时降级为无引用，不让一条脏数据毁掉整轮收口 */
    private String encodeQuietly(List<RagCitation> citations) {
        try {
            return this.ragCitationService.encode(citations);
        }
        catch (RuntimeException e) {
            log.warn("引用编码失败，已降级为无引用：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 取本轮流式输出的 token 上限，供模型选项使用。
     */
    public int maxOutputTokens() {
        return this.chatProperties.getMaxOutputTokens();
    }

}
