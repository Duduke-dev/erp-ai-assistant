package com.duduke.erp.service.chart;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.duduke.erp.service.tool.ToolNames;
import com.duduke.erp.service.tool.trace.ToolTraceKeys;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 图表方案 Tool：让模型在拿到业务数据后，只声明「画什么图、标题叫什么」。
 * <p>
 * <b>入参 schema 刻意收紧</b>：{@code additionalProperties:false}，
 * 只允许 {@code type}（枚举约束）与 {@code title} 两个字段。
 * 模型拿不到列名、拿不到数据，因此既无法编造字段，
 * 也没有办法通过这个入口注入额外内容。
 * <p>
 * <b>它不是业务查询工具</b>：不查库、不返回数据，只登记一个方案；
 * 真正的数据来自 {@link ToolResultRecorder} 暂存的本轮 Tool 结果，
 * 由 {@link ChartCompiler} 在后端编译。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChartPlanToolCallback implements ToolCallback {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "type": {
                  "type": "string",
                  "description": "图表类型，必须是以下之一：bar / line / pie / area / scatter / heatmap / funnel / gauge",
                  "enum": ["bar", "line", "pie", "area", "scatter", "heatmap", "funnel", "gauge"]
                },
                "title": {
                  "type": "string",
                  "description": "图表标题，简短概括这张图在展示什么"
                }
              },
              "required": ["type"],
              "additionalProperties": false
            }
            """;

    private final ObjectMapper objectMapper;

    /** 按 traceId 暂存本轮方案；问答收口时必须 {@link #clear} */
    private final Map<String, ChartPlan> plans = new ConcurrentHashMap<>();

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(ToolNames.CHART_PLAN)
                .description("为刚刚取得的业务数据选择图表类型与标题。"
                        + "仅在已经调用过业务查询工具、且结果适合可视化时调用；"
                        + "只输出图表类型和标题，不要输出数据。")
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    /**
     * {@link ToolCallback} 的抽象方法。本 Tool 需要 traceId 才能把方案归到本轮，
     * 缺少上下文时走 {@link #call(String, ToolContext)} 的拒绝分支。
     */
    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    /**
     * 登记图表方案。
     *
     * @return 给模型看的回执；参数不合法时返回<b>可改正的提示</b>，
     *         模型看到后能自行重试（这比直接抛异常终止整轮问答好得多）
     */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        ChartType type = null;
        String title = null;
        try {
            JsonNode node = this.objectMapper.readTree(toolInput);
            type = ChartType.fromCode(node.path("type").asString(null));
            title = node.path("title").asString(null);
        }
        catch (RuntimeException e) {
            return "图表方案不是合法 JSON，请只输出 type 与 title 两个字段。";
        }

        if (type == null) {
            return "图表类型不支持或缺失，请从以下类型中选择："
                    + ChartType.supportedCodes() + "。";
        }

        String traceId = traceIdOf(toolContext);
        if (traceId == null) {
            // 拿不到 traceId 就无法把方案归到本轮，宁可拒绝也不登记成无人认领的方案
            return "当前无法登记图表方案，请直接以文字回答。";
        }
        this.plans.put(traceId, new ChartPlan(type, title));
        log.debug("已登记图表方案：traceId={}, type={}, title={}", traceId, type.code(), title);
        return "图表方案已记录：" + type.code() + "。请继续给出文字结论。";
    }

    /** 取本轮登记的图表方案；未登记时返回 null */
    public ChartPlan planOf(String traceId) {
        return traceId == null ? null : this.plans.get(traceId);
    }

    /** 清理某轮方案。必须在问答收口处调用 */
    public void clear(String traceId) {
        if (traceId != null) {
            this.plans.remove(traceId);
        }
    }

    private String traceIdOf(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(ToolTraceKeys.TRACE_ID);
        return value == null ? null : value.toString();
    }

}
