package com.duduke.erp.entity.po;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.duduke.erp.component.mybatisplus.JsonbStringTypeHandler;

import lombok.Data;

/**
 * 对话消息。
 * <p>
 * autoResultMap 必须开启，否则自定义 typeHandler 不生效，jsonb 列会读出原始字符串或 null。
 */
@Data
@TableName(value = "chat_message", autoResultMap = true)
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String entCode;

    private String conversationId;

    /** user / assistant / system */
    private String role;

    private String content;

    private String modelId;

    /** auto / data / knowledge */
    private String mode;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Long elapsedMs;

    /** completed / cancelled / failed */
    private String status;

    /** 版本化图表协议 JSON，无需图表或编码失败时为 null */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String chartSpec;

    /** 本轮实际召回的引用证据 JSON 数组 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String ragCitations;

    /** 本轮 Tool 调用摘要 JSON 数组 */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String toolCalls;

    private LocalDateTime createdAt;

}
