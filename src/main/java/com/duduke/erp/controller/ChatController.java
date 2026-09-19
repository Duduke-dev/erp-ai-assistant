package com.duduke.erp.controller;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import com.duduke.erp.entity.dto.AskDTO;
import com.duduke.erp.entity.vo.AskVO;
import com.duduke.erp.entity.vo.ChatMessageVO;
import com.duduke.erp.entity.vo.ConversationVO;
import com.duduke.erp.entity.vo.StreamDelta;
import com.duduke.erp.entity.vo.StreamError;
import com.duduke.erp.entity.vo.StreamEventType;
import com.duduke.erp.entity.vo.StreamWarning;
import com.duduke.erp.service.AssistantService;
import com.duduke.erp.service.AssistantLifecycleService;
import com.duduke.erp.service.BusinessDataTurnGuard;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

/**
 * 对话接口。
 * <p>
 * 会话标识用路径参数（{@code /conversations/{conversationId}}）而非请求体里的字段，
 * 与业务模块的写法保持一致；提问是有副作用的写操作，用 POST。
 * <p>
 * 流式接口用 {@link SseEmitter} 而非返回 {@code Flux}：本项目是 servlet 栈
 * （{@code spring-boot-starter-web}），webflux 仅在 classpath 上提供 Reactor 原语。
 * 返回 {@code Flux} 会走 MVC 的 ReactiveTypeHandler 适配层，异步与错误路径绕且难调试；
 * {@code SseEmitter} 是 servlet 原生方案，取消信号语义明确。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    /** SSE 连接超时。模型长回答可能数分钟，取 5 分钟；超时走 onTimeout 收口为取消 */
    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    /** 会话标识响应头。前端可在首帧到达前据此更新当前会话 */
    public static final String CONVERSATION_ID_HEADER = "X-Conversation-Id";

    /**
     * 回答里的方括号引用编号，如 {@code [1]}。
     * <p>
     * 只用于兜底判定「模型声称引用了资料，但一条证据都没有」——见
     * {@code finishNormally} 里的说明。编号校验本身由 {@code RagCitationService} 负责，
     * 这里不重复它的严格规则，宁可宽松：漏报只是少一条提示，误报会打扰用户。
     */
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[\\d{1,3}]");

    /**
     * 首轮缓冲拦截时，代替模型原文下发给用户的文本。
     * <p>
     * 措辞要说明「为什么没有答案」并给出下一步，而不是一句冷冰冰的拒绝——
     * 用户此时并不知道自己触发了哪条规则。
     */
    private static final String DATA_MISSING_REPLY =
            "本轮没有查到可用的业务数据。为避免给出没有依据的数字，这里不提供推测性回答——"
                    + "请确认查询范围（时间区间、仓库、产品等），或换一种问法再试。";

    private final AssistantService assistantService;

    private final AssistantLifecycleService assistantLifecycleService;

    /** 用于流式收尾时判定「本该查库但本轮没拿到数据」——见 finishNormally 里的说明 */
    private final BusinessDataTurnGuard businessDataTurnGuard;

    /**
     * 提问（非流式）。返回完整回答、引用证据与 token 用量。
     */
    @SaCheckPermission("chat:ask")
    @PostMapping("/ask")
    public AskVO ask(@RequestBody AskDTO request) {
        return this.assistantService.ask(request);
    }

    /**
     * 提问（流式 SSE）。
     * <p>
     * <b>准备阶段的异常以普通 JSON 返回</b>：参数非法、会话不属于当前用户这类问题
     * 发生在 SSE 连接建立之前，此时还能给出正确的 HTTP 状态码；
     * 一旦开始推送事件就改不了了，只能作为 {@code error} 事件下发。
     * <p>
     * 会话标识同时通过响应头 {@code X-Conversation-Id} 返回，便于前端在首帧到达前就更新
     * 当前会话——新建会话的场景下，前端需要立刻知道新会话的标识。
     * <p>
     * <b>为什么返回 {@code ResponseEntity<SseEmitter>} 而不是裸 {@code SseEmitter}：
     * 裸返回时响应头由 MVC 在方法返回后才提交，此处无法再写入自定义头</b>；
     * 用 ResponseEntity 可在返回前把头放进响应。
     */
    @SaCheckPermission("chat:ask")
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> askStream(@RequestBody AskDTO request) {
        AssistantService.StreamPreparation preparation = this.assistantService.prepareStream(request);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onTimeout(() -> {
            // 超时按取消处理：已生成内容照常落库，避免用户刷新后回答消失
            log.info("SSE 超时，按取消收口：conversationId={}",
                    preparation.conversation().getConversationId());
            emitter.complete();
        });

        try {
            emitter.send(SseEmitter.event()
                    .name(StreamEventType.META.eventName())
                    .data(Map.of(
                            "conversationId", preparation.conversation().getConversationId(),
                            "mode", preparation.mode())));
        }
        catch (java.io.IOException e) {
            // 连首个 meta 都发不出去，说明客户端已断开，不必再启动模型调用
            log.debug("SSE 握手失败，客户端可能已断开：{}", e.getMessage());
            emitter.complete();
            return ResponseEntity.ok()
                    .header(CONVERSATION_ID_HEADER, preparation.conversation().getConversationId())
                    .body(emitter);
        }

        startStreaming(emitter, preparation);
        return ResponseEntity.ok()
                .header(CONVERSATION_ID_HEADER, preparation.conversation().getConversationId())
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    /**
     * 启动模型流并桥接到 SSE。
     * <p>
     * 三条终止路径（完成 / 异常 / 取消）都汇入 {@link AssistantLifecycleService} 的收口，
     * 由其内部的 CAS 保证只落库一次。
     * <p>
     * <b>客户端的断开信号走 {@code onCompletion}</b>：Tomcat 检测到连接关闭时会触发它。
     * 此时必须主动 dispose 上游订阅，否则模型会继续生成、继续计费
     * （用户点了「停止」却仍在扣 token）。
     */
    private void startStreaming(SseEmitter emitter, AssistantService.StreamPreparation preparation) {
        var conversation = preparation.conversation();
        var state = this.assistantLifecycleService.newState(preparation.traceId());
        var finalized = new AtomicBoolean(false);
        long startedAt = System.currentTimeMillis();
        // 只有「要业务数据」的问题才启用首轮缓冲门控；其余场景完全直通，不加任何延迟
        var gate = new DeltaGate(this.businessDataTurnGuard.requiresCurrentBusinessData(
                preparation.mode(), preparation.question()));

        // 上游订阅句柄。用 AtomicReference 承载：onCompletion 在正常完成时也会触发，
        // 那时 subscription 早已存在，但闭包捕获的是赋值前的引用，直接用局部变量会拿到 null。
        var subscriptionRef = new AtomicReference<Disposable>();

        emitter.onCompletion(() -> {
            Disposable subscription = subscriptionRef.get();
            if (subscription != null && !subscription.isDisposed()) {
                log.debug("SSE 连接关闭，取消上游模型调用：conversationId={}",
                        conversation.getConversationId());
                subscription.dispose();
            }
            // 连接关闭但收口尚未发生（客户端中途断开）→ 按取消落库已生成内容
            this.assistantLifecycleService.onCancel(
                    state, conversation, preparation.mode(), startedAt, finalized, 0, 0);
        });
        emitter.onError(error -> {
            Disposable subscription = subscriptionRef.get();
            cancelUpstream(subscription, state, preparation, startedAt, finalized);
        });

        Disposable subscription = this.assistantService.streamModel(
                        conversation, preparation.question(), preparation.mode(),
                        preparation.knowledgeBaseId(), preparation.traceId())
                .subscribe(
                        response -> handleDelta(emitter, state, response, gate, preparation),
                        error -> finishWithError(emitter, state, preparation, startedAt, finalized, error),
                        () -> finishNormally(emitter, state, preparation, startedAt, finalized, gate));
        subscriptionRef.set(subscription);
    }

    /** 单帧处理：门控放行后转发文本增量，空帧跳过 */
    private void handleDelta(SseEmitter emitter, AssistantLifecycleService.StreamState state,
                             ChatResponse response, DeltaGate gate,
                             AssistantService.StreamPreparation preparation) {
        String text = this.assistantLifecycleService.onDelta(state, response);
        if (text == null) {
            return;
        }
        if (gate.shouldBuffer()) {
            // 还没放行：检查本轮是否已经拿到非空工具结果（内存查询，微秒级，可每帧做）
            if (this.businessDataTurnGuard.hasBusinessResult(preparation.traceId())) {
                for (String pending : gate.release()) {
                    sendDelta(emitter, pending);
                }
            }
            else {
                gate.buffer(text);
                return;
            }
        }
        sendDelta(emitter, text);
    }

    /** 发送单个增量。失败通常意味着客户端已断开，不在此处收口，交给 onError 统一处理 */
    private void sendDelta(SseEmitter emitter, String text) {
        try {
            emitter.send(SseEmitter.event()
                    .name(StreamEventType.DELTA.eventName())
                    .data(new StreamDelta(text)));
        }
        catch (Exception e) {
            log.debug("SSE 增量发送失败：{}", e.getMessage());
        }
    }

    /** 正常结束：下发引用（若有）与 done */
    private void finishNormally(SseEmitter emitter, AssistantLifecycleService.StreamState state,
                                AssistantService.StreamPreparation preparation,
                                long startedAt, AtomicBoolean finalized, DeltaGate gate) {
        // 门控始终未放行 → 本轮全程没有拿到任何可用的业务数据，模型是在凭印象作答。
        // 丢弃已暂存的内容，改发说明文本；**落库也用同一段文本**（传 answerOverride），
        // 否则界面显示「没查到」、库里存着编造内容，两者对不上。
        String answerOverride = null;
        if (!gate.isReleased()) {
            int dropped = gate.discard();
            log.warn("流式回答因缺少本轮业务数据被拦截：conversationId={}, 丢弃增量={}",
                    preparation.conversation().getConversationId(), dropped);
            answerOverride = DATA_MISSING_REPLY;
        }
        var outcome = this.assistantLifecycleService.onComplete(
                state, preparation.conversation(), preparation.mode(), startedAt, finalized,
                answerOverride, 0, 0);
        if (!outcome.handoff()) {
            return;
        }
        try {
            // 被拦截时把说明补发出去——用户原本会看到的正文已经被丢弃了
            if (answerOverride != null) {
                sendDelta(emitter, answerOverride);
            }
            if (outcome.citations() != null) {
                emitter.send(SseEmitter.event()
                        .name(StreamEventType.CITATIONS.eventName())
                        .data(outcome.citations()));
            }
            // 数据缺失提示：本轮没有任何非空 Tool 结果。
            //
            // 门控启用时的这种情况已被 DeltaGate 拦下（正文被替换为说明文本，answerOverride != null），
            // 此处不重复提示；真正需要它的是**未启用门控**却仍没查到数据的场景——
            // 例如问题不含数据意图词、没被判定为「要业务数据」，但模型自己决定查一下却没查到。
            if (answerOverride == null
                    && !this.businessDataTurnGuard.hasBusinessResult(preparation.traceId())) {
                emitter.send(SseEmitter.event()
                        .name(StreamEventType.WARNING.eventName())
                        .data(new StreamWarning(
                                "本轮未查到业务数据：回答中的数字可能不是来自系统查询，请谨慎采信。")));
            }
            // 第二条兜底：回答里出现了 [编号]，但**一条证据都没有**——
            // 说明那些编号是模型自己编的（既没有 Advisor 召回，也没有知识工具调用）。
            // 判据用「citations 为空 + 回答含编号」而不是去查工具调用记录：
            // citations 是引用校验的产物，它空着就等于「没有可验证的来源」，语义正好。
            if ((outcome.citations() == null || outcome.citations().citations().isEmpty())
                    && CITATION_PATTERN.matcher(
                            state.content() == null ? "" : state.content()).find()) {
                emitter.send(SseEmitter.event()
                        .name(StreamEventType.WARNING.eventName())
                        .data(new StreamWarning(
                                "回答中的 [编号] 引用没有对应的资料检索记录，来源无法验证，请谨慎采信。")));
            }
            if (outcome.done() != null) {
                emitter.send(SseEmitter.event()
                        .name(StreamEventType.DONE.eventName())
                        .data(outcome.done()));
            }
        }
        catch (Exception e) {
            log.debug("SSE 收尾事件发送失败：{}", e.getMessage());
        }
        emitter.complete();
    }

    /** 异常结束：下发 error 事件 */
    private void finishWithError(SseEmitter emitter, AssistantLifecycleService.StreamState state,
                                 AssistantService.StreamPreparation preparation,
                                 long startedAt, AtomicBoolean finalized, Throwable error) {
        var outcome = this.assistantLifecycleService.onError(
                state, preparation.conversation(), preparation.mode(),
                startedAt, finalized, error, 0, 0);
        if (!outcome.handoff()) {
            return;
        }
        // detail 传 null：这里是「收口返回了失败但没带错误事件」的兜底，
        // 本来就没有原始异常可给（有异常的情况由 AssistantLifecycleService 填充）
        StreamError event = outcome.error() == null
                ? new StreamError("STREAM_ERROR", "回答生成失败，请稍后重试", null)
                : outcome.error();
        try {
            emitter.send(SseEmitter.event()
                    .name(StreamEventType.ERROR.eventName())
                    .data(event));
        }
        catch (Exception e) {
            log.debug("SSE 错误事件发送失败：{}", e.getMessage());
        }
        emitter.complete();
    }

    /**
     * 首轮缓冲门控：把「模型不查数据就编数字」这件事从源头挡住。
     *
     * <h3>为什么需要它</h3>
     * 流式路径原先没有任何数据门控（{@code BusinessDataTurnGuard} 的类注释写着
     * 「刻意不做流式门控」），而 {@code tool_choice=required} 那条路实测会让模型
     * 陷入工具调用循环，不可用。于是模型完全可以跳过查询、直接编出一份格式漂亮的表格，
     * 而 2026-09-19 确实发生过（编出 68 个客户，而客户表总共只有 8 个）。
     *
     * <h3>工作方式</h3>
     * 只有当问题被判定为「要业务数据」时才启用（别的场景完全直通，不增加任何延迟）：
     * <ol>
     *   <li>增量先暂存**不下发**；</li>
     *   <li>一旦本轮出现「成功且非空」的工具结果 → 立即放行，补发暂存并转为实时下发；</li>
     *   <li>流结束仍未放行 → 说明模型全程没查数据 → **丢弃暂存**，改发
     *       {@link #DATA_MISSING_REPLY}。</li>
     * </ol>
     * 正常场景下工具调用发生得很早（实测 4~308ms），用户几乎感觉不到缓冲。
     */
    private static final class DeltaGate {

        /** 是否需要门控（问题被判定为要业务数据才为 true） */
        private final boolean enabled;

        /** 已暂存但尚未下发的增量 */
        private final java.util.List<String> buffered = new java.util.ArrayList<>();

        /** 是否已放行（一旦放行就不再缓冲） */
        private boolean released;

        DeltaGate(boolean enabled) {
            this.enabled = enabled;
            this.released = !enabled;
        }

        /** true 表示本次增量应暂存而非下发 */
        synchronized boolean shouldBuffer() {
            return this.enabled && !this.released;
        }

        synchronized void buffer(String text) {
            this.buffered.add(text);
        }

        /** 放行并返回需要补发的内容 */
        synchronized java.util.List<String> release() {
            this.released = true;
            java.util.List<String> pending = java.util.List.copyOf(this.buffered);
            this.buffered.clear();
            return pending;
        }

        /** 是否已放行；未放行即「本轮没有任何业务数据」 */
        synchronized boolean isReleased() {
            return this.released;
        }

        /** 丢弃暂存内容，返回被丢弃的增量条数（供日志核对拦截是否合理） */
        synchronized int discard() {
            int dropped = this.buffered.size();
            this.buffered.clear();
            this.released = true;
            return dropped;
        }

    }

    /** 取消上游订阅并收口为 cancelled */
    private void cancelUpstream(Disposable subscription, AssistantLifecycleService.StreamState state,
                                AssistantService.StreamPreparation preparation,
                                long startedAt, AtomicBoolean finalized) {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
        this.assistantLifecycleService.onCancel(
                state, preparation.conversation(), preparation.mode(), startedAt, finalized, 0, 0);
    }

    /**
     * 当前用户的会话列表，最近更新的在前。
     */
    @SaCheckPermission("chat:ask")
    @GetMapping("/conversations")
    public List<ConversationVO> listConversations() {
        return this.assistantService.listConversations();
    }

    /**
     * 会话内的消息列表，按时间正序，含引用证据。
     */
    @SaCheckPermission("chat:ask")
    @GetMapping("/conversations/{conversationId}/messages")
    public List<ChatMessageVO> listMessages(@PathVariable String conversationId) {
        return this.assistantService.listMessages(conversationId);
    }

    /**
     * 删除会话（软删，消息保留以供审计）。
     */
    @SaCheckPermission("chat:ask")
    @DeleteMapping("/conversations/{conversationId}")
    public void deleteConversation(@PathVariable String conversationId) {
        this.assistantService.deleteConversation(conversationId);
    }

}
