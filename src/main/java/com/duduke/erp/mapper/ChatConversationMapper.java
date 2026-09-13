package com.duduke.erp.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duduke.erp.entity.po.ChatConversation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 对话会话数据访问。
 * <p>
 * 带聚合或 {@code ON CONFLICT} 的语句统一用 {@code @InterceptorIgnore(tenantLine = "true")}
 * 关掉租户插件、由 SQL 显式带 {@code ent_code}：插件改写复杂语句容易出错，
 * 而这类语句的正确性直接关系到会话越权，宁可显式写清楚。
 */
public interface ChatConversationMapper extends BaseMapper<ChatConversation> {

    /**
     * 幂等插入会话。
     * <p>
     * 并发首轮提问可能同时创建同一会话，用 PostgreSQL 的
     * {@code ON CONFLICT ... DO NOTHING} 保证不抛重复键。
     *
     * @return 实际插入行数，0 表示会话已存在
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            INSERT INTO chat_conversation
                (ent_code, conversation_id, user_id, title, model_id,
                 message_count, total_tokens, deleted,
                 created_at, updated_at)
            VALUES (#{entCode}, #{conversationId}, #{userId}, #{title}, #{modelId},
                    0, 0, FALSE,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (ent_code, conversation_id) DO NOTHING
            """)
    int insertIgnore(@Param("entCode") String entCode,
                     @Param("conversationId") String conversationId,
                     @Param("userId") Long userId,
                     @Param("title") String title,
                     @Param("modelId") String modelId);

    /**
     * 累加会话统计。用 SQL 自增而非「查出来 +1 再写回」，
     * 避免并发提问时丢失更新。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE chat_conversation
            SET message_count = message_count + #{messageDelta},
                total_tokens  = total_tokens + #{tokenDelta},
                updated_at    = CURRENT_TIMESTAMP
            WHERE ent_code = #{entCode}
              AND conversation_id = #{conversationId}
            """)
    int accumulateStats(@Param("entCode") String entCode,
                        @Param("conversationId") String conversationId,
                        @Param("messageDelta") int messageDelta,
                        @Param("tokenDelta") int tokenDelta);

    /**
     * 取会话，限定归属：{@code user_id} 参与条件，
     * 使越权访问他人会话表现为「不存在」而非「无权限」，不泄露会话是否存在。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT * FROM chat_conversation
            WHERE ent_code = #{entCode}
              AND conversation_id = #{conversationId}
              AND user_id = #{userId}
              AND deleted = FALSE
            """)
    ChatConversation selectOwned(@Param("entCode") String entCode,
                                 @Param("conversationId") String conversationId,
                                 @Param("userId") Long userId);

    /**
     * 软删会话。与之关联的消息不物理删除，保留审计痕迹。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE chat_conversation
            SET deleted = TRUE,
                updated_at = CURRENT_TIMESTAMP
            WHERE ent_code = #{entCode}
              AND conversation_id = #{conversationId}
              AND user_id = #{userId}
              AND deleted = FALSE
            """)
    int softDelete(@Param("entCode") String entCode,
                   @Param("conversationId") String conversationId,
                   @Param("userId") Long userId);

}
