package com.duduke.erp.controller;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.duduke.erp.entity.dto.AskDTO;
import com.duduke.erp.entity.vo.AskVO;
import com.duduke.erp.entity.vo.ChatMessageVO;
import com.duduke.erp.entity.vo.ConversationVO;
import com.duduke.erp.entity.vo.StreamDelta;
import com.duduke.erp.entity.vo.StreamError;
import com.duduke.erp.entity.vo.StreamEventType;
import com.duduke.erp.service.AssistantService;
import com.duduke.erp.service.AssistantLifecycleService;

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

    private final AssistantService assistantService;

    private final AssistantLifecycleService assistantLifecycleService;

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
        var state = this.assistantLifecycleService.newState();
        var finalized = new AtomicBoolean(false);
        long startedAt = System.currentTimeMillis();

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
                        conversation, preparation.question(),
                        preparation.knowledgeMode(), preparation.knowledgeBaseId())
                .subscribe(
                        response -> handleDelta(emitter, state, response),
                        error -> finishWithError(emitter, state, preparation, startedAt, finalized, error),
                        () -> finishNormally(emitter, state, preparation, startedAt, finalized));
        subscriptionRef.set(subscription);
    }

    /** 单帧处理：只转发文本增量，空帧跳过 */
    private void handleDelta(SseEmitter emitter, AssistantLifecycleService.StreamState state,
                             ChatResponse response) {
        String text = this.assistantLifecycleService.onDelta(state, response);
        if (text == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(StreamEventType.DELTA.eventName())
                    .data(new StreamDelta(text)));
        }
        catch (Exception e) {
            // 发送失败通常意味着客户端已断开。不在这里收口，
            // 让 onError 回调统一处理，避免两条路径并发收口。
            log.debug("SSE 增量发送失败：{}", e.getMessage());
        }
    }

    /** 正常结束：下发引用（若有）与 done */
    private void finishNormally(SseEmitter emitter, AssistantLifecycleService.StreamState state,
                                AssistantService.StreamPreparation preparation,
                                long startedAt, AtomicBoolean finalized) {
        var outcome = this.assistantLifecycleService.onComplete(
                state, preparation.conversation(), preparation.mode(), startedAt, finalized, 0, 0);
        if (!outcome.handoff()) {
            return;
        }
        try {
            if (outcome.citations() != null) {
                emitter.send(SseEmitter.event()
                        .name(StreamEventType.CITATIONS.eventName())
                        .data(outcome.citations()));
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
        StreamError event = outcome.error() == null
                ? new StreamError("STREAM_ERROR", "回答生成失败，请稍后重试")
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
