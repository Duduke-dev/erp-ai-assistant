package com.duduke.erp.service.tool.dynamic;

import com.duduke.erp.entity.po.LlmTool;

import lombok.RequiredArgsConstructor;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.databind.ObjectMapper;

/**
 * 把一个数据库动态 Tool 暴露成 Spring AI 的 {@link ToolCallback}。
 * <p>
 * 名字、描述与入参 Schema 全部来自数据库配置，原样下发给模型——
 * 这正是「不改代码就能给助手加查询能力」的实现方式。
 * <p>
 * 执行与序列化委托给 {@link DatabaseToolExecutor} 与 Jackson；
 * 本类只做适配，不含任何业务判断。
 * <p>
 * {@code toolDefinition} 由 {@link DatabaseToolCallbackFactory} 预先构建后传入，
 * 而不是在这里现算：它是不可变的，每次调用重建纯属浪费。
 */
@RequiredArgsConstructor
public class DatabaseToolCallback implements ToolCallback {

    private final LlmTool tool;

    private final DatabaseToolExecutor executor;

    private final ObjectMapper objectMapper;

    private final ToolDefinition toolDefinition;

    @Override
    public ToolDefinition getToolDefinition() {
        return this.toolDefinition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return ToolMetadata.builder().build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        ToolQueryResult result = this.executor.execute(this.tool, toolInput);
        try {
            return this.objectMapper.writeValueAsString(result.rows());
        }
        catch (RuntimeException e) {
            throw new IllegalStateException("动态 Tool 结果序列化失败", e);
        }
    }

}
