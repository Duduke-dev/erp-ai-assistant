package com.duduke.erp.entity.po;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 对话会话。
 * <p>
 * 对外用 {@code conversationId}（UUID）标识，不暴露自增主键，
 * 避免通过 id 猜测他人会话。
 */
@Data
@TableName("chat_conversation")
public class ChatConversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String entCode;

    /** 会话对外标识（UUID） */
    private String conversationId;

    private Long userId;

    /** 由首条提问截断生成 */
    private String title;

    private String modelId;

    private Integer messageCount;

    private Integer totalTokens;

    /** 软删标记，归档后置 true */
    private Boolean deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
