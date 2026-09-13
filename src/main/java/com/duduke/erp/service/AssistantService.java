package com.duduke.erp.service;

import java.util.List;

import com.duduke.erp.component.springai.AssistantClientProvider;
import com.duduke.erp.component.springai.ChatMemoryAdvisorFactory;
import com.duduke.erp.config.ChatProperties;
import com.duduke.erp.entity.dto.AskDTO;
import com.duduke.erp.entity.po.ChatConversation;
import com.duduke.erp.entity.po.ChatMessage;
import com.duduke.erp.entity.po.KnowledgeBase;
import com.duduke.erp.entity.vo.AskVO;
import com.duduke.erp.entity.vo.ChatMessageVO;
import com.duduke.erp.entity.vo.ConversationVO;
import com.duduke.erp.entity.vo.RagCitation;
import com.duduke.erp.tenant.TenantContext;
import com.duduke.erp.tenant.TenantContextAccessor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 对话编排。
 * <p>
 * 一轮问答的完整步骤：
 * <ol>
 *   <li>校验提问 → 解析/创建会话（归属校验）</li>
 *   <li>落库用户消息（必须在调模型之前，保证刷新页面能看到刚发的问题）</li>
 *   <li>装配 Advisor：记忆 Advisor + （可选）RAG Advisor</li>
 *   <li>调用模型，从响应 metadata 取回本轮召回文档</li>
 *   <li>用真实证据校验引用编号 → 落库助手消息 → 返回</li>
 * </ol>
 *
 * <h3>两种模式</h3>
 * <ul>
 *   <li>{@code auto} —— 默认。挂 RAG（用 auto 参数，阈值偏精度），
 *       后续 M3 会在此基础上加业务 Tool。知识只是辅助。</li>
 *   <li>{@code knowledge} —— 纯知识库问答。用 knowledge 参数（阈值偏召回），
 *       系统提示词换成「只依据资料作答」。</li>
 * </ul>
 * M3 之前的 Tool 尚未接入，因此 auto 与 knowledge 目前的差异
 * 体现在 RAG 参数与系统提示词上；Tool 接入后 auto 会额外挂 Tool Calling Advisor。
 *
 * <h3>为什么失败也要落库</h3>
 * 助手消息无论成功、被取消还是失败都会写入一条记录（status 区分）。
 * 只写成功的会让前端在失败后看不到任何痕迹，用户无法判断是「没发出去」还是「模型报错」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantService {

    /** 模式：业务问答（含知识辅助，后续加 Tool） */
    public static final String MODE_AUTO = "auto";

    /** 模式：纯知识库问答 */
    public static final String MODE_KNOWLEDGE = "knowledge";

    private final AssistantClientProvider assistantClientProvider;

    private final ChatMemoryAdvisorFactory chatMemoryAdvisorFactory;

    private final ChatHistoryService chatHistoryService;

    private final RagAnswerService ragAnswerService;

    private final RagCitationService ragCitationService;

    private final ChatProperties chatProperties;

    private final ChatModel chatModel;

    /**
     * 非流式问答。
     */
    public AskVO ask(AskDTO request) {
        String mode = normalizeMode(request.mode());
        String question = this.chatHistoryService.requireQuestion(request.question());
        boolean knowledgeMode = MODE_KNOWLEDGE.equals(mode);

        ChatConversation conversation = this.chatHistoryService.resolveConversation(
                request.conversationId(), question, resolveModelName());
        this.chatHistoryService.ensureConversationWritable(conversation);

        // 先落库用户消息：模型调用可能耗时数十秒，等回答一起写会让用户以为消息丢了
        this.chatHistoryService.saveUserMessage(conversation, question, mode);

        long startedAt = System.currentTimeMillis();
        try {
            ChatResponse response = invokeModel(conversation, question,
                    knowledgeMode, request.knowledgeBaseId());
            return buildSuccessResult(conversation, mode, response, System.currentTimeMillis() - startedAt);
        }
        catch (RuntimeException e) {
            long elapsed = System.currentTimeMillis() - startedAt;
            // 失败也落库，前端才能区分「没发出去」与「模型报错」
            this.chatHistoryService.saveAssistantMessage(conversation, null, mode,
                    0, 0, 0, elapsed, ChatHistoryService.STATUS_FAILED, e.getMessage(), 0, null);
            log.error("对话调用失败：conversationId={}, mode={}", conversation.getConversationId(), mode, e);
            throw e;
        }
        finally {
            // 线程复用，必须清理，否则下一次请求会读到本轮证据导致引用串档
            RagContextFormatter.clearRecalledDocuments();
        }
    }

    /**
     * 组装 Advisor 并调用模型。
     * <p>
     * Advisor 顺序：记忆在前、RAG 在后。
     * 记忆 Advisor 需要先把历史消息拼进 prompt，RAG 才能基于「带历史的完整请求」做检索；
     * 反过来则检索只能看到当前这一句，多轮追问（「那上个月呢」）会检索不到任何东西。
     */
    private ChatResponse invokeModel(ChatConversation conversation, String question,
                                     boolean knowledgeMode, Long knowledgeBaseId) {
        return streamingCall(conversation, question, knowledgeMode, knowledgeBaseId)
                .call()
                .chatResponse();
    }

    /**
     * 发起流式调用，返回逐帧的 {@link ChatResponse}。
     * <p>
     * 与 {@link #invokeModel} 共用同一套 Advisor 装配（{@link #streamingCall}），
     * 保证流式与非流式的检索口径、记忆行为完全一致——
     * 两条链路各写一份装配代码，迟早会出现「非流式能召回、流式不能」这类差异。
     * <p>
     * 用 {@code stream().chatResponse()} 而非 {@code stream().content()}：
     * 后者只给文本，丢掉了 metadata，而 token 用量与召回文档只能在 metadata 里拿到。
     * <p>
     * <b>租户上下文用 contextWrite 显式带入</b>：流一旦订阅就会切到 Reactor 线程，
     * 而 RAG 检索要按 {@code ent_code} 过滤向量。仅靠 ThreadLocal 在切换后会失效，
     * 表现为「检索到别人的数据」或「检索不到任何东西」。
     * 这里在 servlet 线程捕获快照写进 Reactor Context，
     * 由注册的 {@code TenantContextAccessor} 在每次线程切换时自动恢复。
     */
    public Flux<ChatResponse> streamModel(ChatConversation conversation, String question,
                                          boolean knowledgeMode, Long knowledgeBaseId) {
        String entCode = TenantContext.getEntCode();
        Long userId = TenantContext.getUserId();

        return streamingCall(conversation, question, knowledgeMode, knowledgeBaseId)
                .stream()
                .chatResponse()
                .contextWrite(context -> context.put(
                        TenantContextAccessor.KEY,
                        new TenantContextAccessor.TenantSnapshot(entCode, userId)));
    }

    /**
     * 统一的调用装配：系统提示词 + 用户提问 + 记忆 Advisor +（可选）RAG Advisor。
     */
    private ChatClient.ChatClientRequestSpec streamingCall(
            ChatConversation conversation, String question,
            boolean knowledgeMode, Long knowledgeBaseId) {
        MessageChatMemoryAdvisor memoryAdvisor = this.chatMemoryAdvisorFactory.create();

        return this.assistantClientProvider.client()
                .prompt()
                .system(knowledgeMode
                        ? this.chatProperties.getKnowledgePrompt()
                        : this.chatProperties.getBusinessPrompt())
                .user(question)
                .advisors(advisor -> {
                    advisor.advisors(memoryAdvisor)
                            .param(ChatMemory.CONVERSATION_ID, conversation.getConversationId());
                    if (knowledgeMode) {
                        // knowledge 模式必须挂 RAG，检索不到就如实说明资料不足
                        advisor.advisors(this.ragAnswerService.prepareAdvisor(knowledgeBaseId, true));
                    }
                });
    }

    /**
     * 流式问答的准备阶段：校验、解析会话、落库提问。
     * <p>
     * 返回的会话与模式供调用方启动流；准备阶段抛出的异常发生在 SSE 连接建立之前，
     * 调用方仍能以普通 JSON 响应返回，不受「SSE 无法改状态码」的限制。
     * <p>
     * <b>知识库标识必须一并带出</b>：它来自请求体，若不随准备结果传递，
     * 启动流时就只能传 null，{@code resolveActive(null)} 会回落到默认库——
     * 表现为「指定了知识库却检索不到任何东西」，且不报错。
     *
     * @return 已校验并落库提问的上下文
     */
    public StreamPreparation prepareStream(AskDTO request) {
        String mode = normalizeMode(request.mode());
        String question = this.chatHistoryService.requireQuestion(request.question());
        ChatConversation conversation = this.chatHistoryService.resolveConversation(
                request.conversationId(), question, resolveModelName());
        this.chatHistoryService.ensureConversationWritable(conversation);
        this.chatHistoryService.saveUserMessage(conversation, question, mode);
        return new StreamPreparation(conversation, question, mode,
                MODE_KNOWLEDGE.equals(mode), request.knowledgeBaseId());
    }

    /**
     * 流式问答的准备结果。
     *
     * @param conversation    已校验归属的会话
     * @param question        规范化后的提问
     * @param mode            规范化后的模式
     * @param knowledgeMode   是否知识问答模式
     * @param knowledgeBaseId 请求指定的知识库，为空时由下游回落到默认库
     */
    public record StreamPreparation(
            ChatConversation conversation,
            String question,
            String mode,
            boolean knowledgeMode,
            Long knowledgeBaseId) {
    }

    /**
     * 从响应中提取答案、用量、召回文档，校验引用后落库并组装返回。
     */
    private AskVO buildSuccessResult(ChatConversation conversation, String mode,
                                     ChatResponse response, long elapsedMs) {
        String answer = response == null || response.getResult() == null
                ? null
                : response.getResult().getOutput().getText();

        List<Document> recalled = RagContextFormatter.recalledDocuments();
        List<RagCitation> citations = this.ragCitationService.validate(answer, recalled);
        if (log.isDebugEnabled()) {
            log.debug("引用校验：召回={}，命中={}", recalled.size(), citations.size());
        }
        String citationsJson = encodeCitationsQuietly(citations);

        Usage usage = response == null ? null : response.getMetadata().getUsage();
        Integer promptTokens = usage == null ? 0 : usage.getPromptTokens();
        Integer completionTokens = usage == null ? 0 : usage.getCompletionTokens();
        Integer totalTokens = usage == null ? 0 : usage.getTotalTokens();

        ChatMessage saved = this.chatHistoryService.saveAssistantMessage(
                conversation, answer, mode,
                promptTokens, completionTokens, totalTokens, elapsedMs,
                ChatHistoryService.STATUS_COMPLETED, null,
                recalled.size(), citationsJson);

        return new AskVO(
                conversation.getConversationId(),
                saved.getId(),
                answer,
                mode,
                citations,
                recalled.size(),
                promptTokens,
                completionTokens,
                totalTokens,
                elapsedMs);
    }

    /**
     * 引用 JSON 体积超限时降级为无引用，不因一条转换失败让整轮问答失败。
     */
    private String encodeCitationsQuietly(List<RagCitation> citations) {
        try {
            return this.ragCitationService.encode(citations);
        }
        catch (RuntimeException e) {
            log.warn("引用编码失败，已降级为无引用：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 列出当前用户的会话。
     */
    public List<ConversationVO> listConversations() {
        return this.chatHistoryService.listConversations().stream()
                .map(c -> new ConversationVO(
                        c.getConversationId(),
                        c.getTitle(),
                        c.getModelId(),
                        c.getMessageCount(),
                        c.getTotalTokens(),
                        c.getCreatedAt(),
                        c.getUpdatedAt()))
                .toList();
    }

    /**
     * 列出会话内的消息（含引用证据）。
     */
    public List<ChatMessageVO> listMessages(String conversationId) {
        return this.chatHistoryService.listMessages(conversationId).stream()
                .map(this::toMessageVO)
                .toList();
    }

    /**
     * 删除会话（软删）。
     */
    public void deleteConversation(String conversationId) {
        this.chatHistoryService.deleteConversation(conversationId);
    }

    private ChatMessageVO toMessageVO(ChatMessage message) {
        List<RagCitation> citations = this.ragCitationService.decode(message.getRagCitations());
        return new ChatMessageVO(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getMode(),
                message.getStatus(),
                message.getErrorMessage(),
                citations,
                message.getRagDocCount(),
                message.getPromptTokens(),
                message.getCompletionTokens(),
                message.getTotalTokens(),
                message.getElapsedMs(),
                message.getCreatedAt());
    }

    /**
     * 规范化模式。未知值一律回落到 auto，不因客户端传错值直接报错——
     * 前端文案变化不该让用户的提问发不出去。
     */
    private String normalizeMode(String mode) {
        if (MODE_KNOWLEDGE.equalsIgnoreCase(mode)) {
            return MODE_KNOWLEDGE;
        }
        return MODE_AUTO;
    }

    /**
     * 取当前实际使用的模型名，用于记录到会话与消息上。
     */
    private String resolveModelName() {
        String name = this.chatModel.getDefaultOptions().getModel();
        return name == null ? "unknown" : name;
    }

    /**
     * 解析本轮知识库，供调用方查询当前生效的知识库。
     */
    public KnowledgeBase resolveKnowledgeBase(Long knowledgeBaseId) {
        return this.ragAnswerService.resolveKnowledgeBase(knowledgeBaseId);
    }

}
