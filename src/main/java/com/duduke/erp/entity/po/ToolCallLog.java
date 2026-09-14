package com.duduke.erp.entity.po;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.duduke.erp.component.mybatisplus.JsonbStringTypeHandler;

import lombok.Data;

/**
 * Tool 调用日志。
 * <p>
 * 旁路记录：写库失败<strong>不得</strong>影响问答主链路，
 * 因此 {@code user_id} / {@code mode} 等字段允许为 null
 * （异步收口路径上未必拿得到完整的调用者上下文）。
 * <p>
 * autoResultMap 必须开启，否则自定义 typeHandler 不生效，jsonb 列会读出原始字符串或 null。
 */
@Data
@TableName(value = "tool_call_log", autoResultMap = true)
public class ToolCallLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String entCode;

    private String conversationId;

    /** 本轮助手消息主键，异步收口时回填 */
    private Long messageId;

    /** 一轮问答共用一个 traceId，用于把同轮的多次 Tool 调用归组 */
    private String traceId;

    private String toolName;

    /** code（@Tool 方法）/ database（动态 SQL Tool） */
    private String toolSource;

    private String modelName;

    /** 调用者主键 */
    private Long userId;

    /** auto / knowledge */
    private String mode;

    /** 模型实际传入的参数 JSON */
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String arguments;

    /** success / error */
    private String status;

    private Long elapsedMs;

    /** 结果行数，由返回 JSON 推断 */
    private Integer resultCount;

    private String errorSummary;

    private LocalDateTime createdAt;

}
