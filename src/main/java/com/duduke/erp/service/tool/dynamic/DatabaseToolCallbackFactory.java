package com.duduke.erp.service.tool.dynamic;

import com.duduke.erp.entity.po.LlmTool;

import lombok.RequiredArgsConstructor;

import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 由动态 Tool 定义装配 {@link DatabaseToolCallback}。
 * <p>
 * 单独成类是为了让 {@code DatabaseToolCallback} 保持"纯适配器"形态：
 * 它不负责拼装 Tool 定义，只持有已经装配好的不可变定义。
 * 装配职责集中在这里，注册表刷新时调一次。
 */
@Component
@RequiredArgsConstructor
public class DatabaseToolCallbackFactory {

    private final DatabaseToolExecutor executor;

    private final ObjectMapper objectMapper;

    /**
     * 装配一个动态 Tool 的 ToolCallback。
     *
     * @param tool 动态 Tool 定义；调用前应已通过 {@code SqlToolValidator} 校验
     */
    public DatabaseToolCallback create(LlmTool tool) {
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(tool.getToolName())
                .description(tool.getToolDesc())
                .inputSchema(tool.getInputSchema())
                .build();
        return new DatabaseToolCallback(tool, this.executor, this.objectMapper, definition);
    }

}
