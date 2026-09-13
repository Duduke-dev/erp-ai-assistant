package com.duduke.erp.mapper;

import java.util.List;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duduke.erp.entity.po.ChatMessage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 对话消息数据访问。
 */
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /**
     * 取会话最近 N 条**成功**消息，按时间正序返回。
     * <p>
     * 供记忆窗口使用。只取 {@code status = 'completed'}：
     * 失败或被取消的消息内容不完整，喂给模型会污染上下文。
     * 先倒序 LIMIT 再正序，保证拿到的是「最近的 N 条」而非「最早的 N 条」。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT * FROM (
                SELECT * FROM chat_message
                WHERE ent_code = #{entCode}
                  AND conversation_id = #{conversationId}
                  AND content IS NOT NULL
                  AND status = 'completed'
                ORDER BY id DESC
                LIMIT #{limit}
            ) recent
            ORDER BY id ASC
            """)
    List<ChatMessage> selectRecentMessages(@Param("entCode") String entCode,
                                           @Param("conversationId") String conversationId,
                                           @Param("limit") int limit);

}
