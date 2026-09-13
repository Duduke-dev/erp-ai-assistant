package com.duduke.erp.component.springai;

import java.util.ArrayList;
import java.util.List;

import com.duduke.erp.entity.po.ChatMessage;
import com.duduke.erp.mapper.ChatMessageMapper;
import com.duduke.erp.tenant.TenantContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 对话记忆仓库。
 * <p>
 * 把 Spring AI 的记忆窗口接到 {@code chat_message} 表上：
 * <b>读</b>时从库里捞最近 N 条成功消息还原成 {@link Message}，
 * <b>写</b>时留空——消息的持久化由 {@code ChatHistoryService} 在业务事务里完成。
 *
 * <h3>为什么 saveAll 是空实现</h3>
 * Spring AI 的 {@code MessageChatMemoryAdvisor} 在每轮对话后会回调 {@code saveAll}。
 * 如果在这里落库，会绕开业务方的状态机与统计逻辑：
 * 失败的行、被取消的行、token 用量、引用证据这些都要在业务侧一并写入，
 * 而不是由 Advisor 在模型调用结束后追加两条裸消息。
 * 因此本类**只承担读取职责**，写入交给业务服务，职责边界清晰。
 *
 * <h3>为什么 findByConversationId 要剔除末尾的 user 消息</h3>
 * Spring AI 记忆 Advisor 的调用顺序是：
 * <pre>
 *   before()  → chatMemory.get(conversationId) 取历史，拼到当前请求前面
 *   调用模型
 *   after()   → chatMemory.add(...) 把本轮的 user/assistant 追加进记忆
 * </pre>
 * 而业务侧在<b>调用模型之前</b>就先把用户提问落库了（否则用户刷新页面会看不到自己刚说的话）。
 * 于是 {@code get()} 返回的历史里已经包含本轮提问，
 * 而 {@code ChatClient.prompt().user(question)} 又带了一份——
 * 模型会看到同一句话出现两次，既浪费 token，也可能诱导重复回答。
 * 剔除末尾的 user 消息即可解决，且与「历史必须来自数据库」不冲突。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JdbcChatMemoryRepository implements ChatMemoryRepository {

    private final ChatMessageMapper chatMessageMapper;

    /**
     * 取会话的历史消息，按时间正序。
     * <p>
     * <b>只取 status = 'completed'</b>：失败或被取消的消息内容不完整，
     * 喂给模型会污染上下文。
     * <p>
     * 记忆窗口的大小由 {@code MessageWindowChatMemory} 负责裁剪，
     * 这里给一个足够的粗筛上限，避免一次捞出整段超长会话。
     */
    @Override
    public List<Message> findByConversationId(String conversationId) {
        String entCode = TenantContext.getEntCode();
        if (!StringUtils.hasText(entCode) || !StringUtils.hasText(conversationId)) {
            return List.of();
        }
        List<ChatMessage> rows = this.chatMessageMapper.selectRecentMessages(
                entCode, conversationId, MEMORY_FETCH_LIMIT);
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Message> messages = new ArrayList<>(rows.size());
        for (ChatMessage row : rows) {
            Message message = toMessage(row);
            if (message != null) {
                messages.add(message);
            }
        }
        return dropTrailingUserMessage(messages);
    }

    /**
     * 空实现，理由见类注释：持久化由业务侧在事务内完成。
     */
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        // no-op：不得在此落库，否则绕开业务状态机与统计
    }

    /**
     * 空实现。会话删除是软删（{@code deleted = TRUE}），
     * 消息保留以供审计；若要物理清理，应由独立的归档任务按租户策略处理，
     * 不应由记忆窗口的 {@code clear()} 触发。
     */
    @Override
    public void deleteByConversationId(String conversationId) {
        // no-op：软删由 ChatConversationMapper.softDelete 处理
    }

    /**
     * 列出会话 ID。仅用于框架内部的全量清理场景，本项目用不到，返回空列表。
     */
    @Override
    public List<String> findConversationIds() {
        return List.of();
    }

    private Message toMessage(ChatMessage row) {
        String content = row.getContent();
        if (!StringUtils.hasText(content)) {
            return null;
        }
        String role = row.getRole();
        if (role == null) {
            return null;
        }
        return switch (role) {
            case "user" -> new UserMessage(content);
            case "assistant" -> new AssistantMessage(content);
            // 历史里的 system 消息不入记忆窗口：系统提示词每轮由编排层重新拼装，
            // 从库里回放会导致提示词重复堆叠。
            default -> null;
        };
    }

    /**
     * 剔除末尾的 user 消息，避免与本轮提问重复。理由见类注释。
     * <p>
     * 必须返回**新的列表**而非 {@code subList} 视图：视图会持有原列表引用，
     * 后续框架若遍历中做修改会抛 {@code ConcurrentModificationException}。
     */
    private List<Message> dropTrailingUserMessage(List<Message> messages) {
        if (messages.isEmpty()) {
            return messages;
        }
        Message last = messages.get(messages.size() - 1);
        if (last.getMessageType() == MessageType.USER) {
            return new ArrayList<>(messages.subList(0, messages.size() - 1));
        }
        return messages;
    }

    /** 粗筛上限：远大于记忆窗口，真正的裁剪由 MessageWindowChatMemory 完成 */
    private static final int MEMORY_FETCH_LIMIT = 100;

}
