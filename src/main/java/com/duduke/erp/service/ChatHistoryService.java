package com.duduke.erp.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duduke.erp.config.ChatProperties;
import com.duduke.erp.entity.po.ChatConversation;
import com.duduke.erp.entity.po.ChatMessage;
import com.duduke.erp.mapper.ChatConversationMapper;
import com.duduke.erp.mapper.ChatMessageMapper;
import com.duduke.erp.tenant.TenantContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 对话历史与会话管理。
 * <p>
 * 职责：会话的创建 / 归属校验 / 统计维护 / 软删，以及消息的落库。
 * 所有写方法都在事务内完成，且**归属校验以 {@code user_id} 为准**——
 * 租户隔离交给 MyBatis-Plus 插件，但同租户内不同用户之间也必须互相看不见。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatHistoryService {

    /** 消息状态：正常完成 */
    public static final String STATUS_COMPLETED = "completed";

    /** 消息状态：用户中断生成 */
    public static final String STATUS_CANCELLED = "cancelled";

    /** 消息状态：调用失败 */
    public static final String STATUS_FAILED = "failed";

    private final ChatConversationMapper conversationMapper;

    private final ChatMessageMapper messageMapper;

    private final ChatProperties chatProperties;

    /**
     * 取会话；不存在则按首条提问创建。同时完成归属校验。
     *
     * @param conversationId 会话标识，为空时新建
     * @param question       首条提问，用于生成标题
     * @param modelId        本轮使用的模型标识
     * @return 会话（保证属于当前用户）
     */
    @Transactional
    public ChatConversation resolveConversation(String conversationId, String question, String modelId) {
        String entCode = TenantContext.requireEntCode();
        Long userId = requireUserId();

        if (StringUtils.hasText(conversationId)) {
            ChatConversation existing = this.conversationMapper.selectOwned(entCode, conversationId, userId);
            if (existing == null) {
                // 不存在与不属于当前用户返回同一提示，不泄露会话是否存在
                throw new IllegalArgumentException("会话不存在或不属于当前用户");
            }
            return existing;
        }

        String generatedId = UUID.randomUUID().toString();
        this.conversationMapper.insertIgnore(entCode, generatedId, userId,
                buildTitle(question), modelId);
        ChatConversation created = this.conversationMapper.selectOwned(entCode, generatedId, userId);
        if (created == null) {
            throw new IllegalStateException("会话创建失败：" + generatedId);
        }
        return created;
    }

    /**
     * 按会话标识读取（含归属校验），不创建。
     */
    public ChatConversation requireOwned(String conversationId) {
        String entCode = TenantContext.requireEntCode();
        Long userId = requireUserId();
        ChatConversation conversation = this.conversationMapper.selectOwned(entCode, conversationId, userId);
        if (conversation == null) {
            throw new IllegalArgumentException("会话不存在或不属于当前用户");
        }
        return conversation;
    }

    /**
     * 列出当前用户的会话，最近更新的在前。
     */
    public List<ChatConversation> listConversations() {
        return this.conversationMapper.selectList(
                Wrappers.<ChatConversation>lambdaQuery()
                        .eq(ChatConversation::getUserId, requireUserId())
                        .eq(ChatConversation::getDeleted, false)
                        .orderByDesc(ChatConversation::getUpdatedAt));
    }

    /**
     * 列出会话内的消息，按时间正序。
     */
    public List<ChatMessage> listMessages(String conversationId) {
        requireOwned(conversationId);
        return this.messageMapper.selectList(
                Wrappers.<ChatMessage>lambdaQuery()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .orderByAsc(ChatMessage::getId));
    }

    /**
     * 软删会话。消息保留，便于审计与问题追溯。
     */
    @Transactional
    public void deleteConversation(String conversationId) {
        String entCode = TenantContext.requireEntCode();
        Long userId = requireUserId();
        int affected = this.conversationMapper.softDelete(entCode, conversationId, userId);
        if (affected == 0) {
            throw new IllegalArgumentException("会话不存在或不属于当前用户");
        }
    }

    /**
     * 保存用户提问。
     * <p>
     * 必须在调用模型**之前**落库：模型调用可能耗时数十秒，
     * 若等回答一起写入，用户中途刷新会看不到自己刚发的问题。
     * <p>
     * 由此带来的副作用是「记忆窗口里已含本轮提问」，由
     * {@code JdbcChatMemoryRepository} 剔除末尾 user 消息解决。
     */
    @Transactional
    public ChatMessage saveUserMessage(ChatConversation conversation, String content, String mode) {
        ChatMessage message = new ChatMessage();
        message.setConversationId(conversation.getConversationId());
        message.setRole("user");
        message.setContent(content);
        message.setModelId(conversation.getModelId());
        message.setMode(mode);
        message.setPromptTokens(0);
        message.setCompletionTokens(0);
        message.setTotalTokens(0);
        message.setElapsedMs(0L);
        message.setStatus(STATUS_COMPLETED);
        message.setCreatedAt(LocalDateTime.now());
        this.messageMapper.insert(message);

        this.conversationMapper.accumulateStats(
                TenantContext.requireEntCode(), conversation.getConversationId(), 1, 0);
        return message;
    }

    /**
     * 保存助手回答。
     *
     * @param status         completed / cancelled / failed
     * @param errorMessage   失败原因摘要，仅 status = failed 时有值
     * @param ragDocCount    本轮通过资格过滤的召回分片数
     * @param citationsJson  引用证据 JSON，无引用时为 null
     */
    @Transactional
    public ChatMessage saveAssistantMessage(ChatConversation conversation, String content, String mode,
                                            Integer promptTokens, Integer completionTokens,
                                            Integer totalTokens, long elapsedMs,
                                            String status, String errorMessage,
                                            Integer ragDocCount, String citationsJson) {
        int prompt = promptTokens == null ? 0 : promptTokens;
        int completion = completionTokens == null ? 0 : completionTokens;
        int total = totalTokens == null ? 0 : (prompt + completion);

        ChatMessage message = new ChatMessage();
        message.setConversationId(conversation.getConversationId());
        message.setRole("assistant");
        message.setContent(content);
        message.setModelId(conversation.getModelId());
        message.setMode(mode);
        message.setPromptTokens(prompt);
        message.setCompletionTokens(completion);
        message.setTotalTokens(total);
        message.setElapsedMs(elapsedMs);
        message.setStatus(status);
        message.setErrorMessage(truncate(errorMessage, 1000));
        message.setRagDocCount(ragDocCount == null ? 0 : ragDocCount);
        message.setToolCallsCount(0);
        message.setRagCitations(citationsJson);
        message.setCreatedAt(LocalDateTime.now());
        this.messageMapper.insert(message);

        this.conversationMapper.accumulateStats(
                TenantContext.requireEntCode(), conversation.getConversationId(), 1, total);
        return message;
    }

    /**
     * 校验会话未超出消息数上限。
     * <p>
     * 不设上限时，超长会话会让每轮请求都携带巨大上下文，
     * token 成本失控且迟早撞上模型窗口。到限后提示用户新建会话，
     * 而不是静默截断——静默截断会让用户以为模型「失忆」。
     */
    public void ensureConversationWritable(ChatConversation conversation) {
        int limit = this.chatProperties.getMaxMessagesPerConversation();
        Integer count = conversation.getMessageCount();
        if (count != null && count >= limit) {
            throw new IllegalArgumentException(
                    "会话消息数已达上限（" + limit + "），请新建会话后继续");
        }
    }

    /**
     * 校验并规范化提问。
     */
    public String requireQuestion(String question) {
        String trimmed = question == null ? "" : question.trim();
        if (!StringUtils.hasText(trimmed)) {
            throw new IllegalArgumentException("提问内容不能为空");
        }
        int max = this.chatProperties.getMaxQuestionLength();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException("提问过长，请控制在 " + max + " 字以内");
        }
        return trimmed;
    }

    /** 由首条提问截断生成标题 */
    private String buildTitle(String question) {
        String text = question == null ? "" : question.strip().replaceAll("\\s+", " ");
        int max = this.chatProperties.getTitleMaxLength();
        if (text.isEmpty()) {
            return "新会话";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private Long requireUserId() {
        Long userId = TenantContext.getUserId();
        if (userId == null) {
            throw new IllegalStateException("当前上下文缺少用户标识");
        }
        return userId;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

}
