package com.duduke.erp.service.chart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

/**
 * 按 traceId 暂存本轮业务 Tool 的结构化结果，供图表编译使用。
 * <p>
 * <b>存在的意义是解耦</b>：模型只输出「图表类型 + 标题」，
 * 真正的数据由这里留存的 Tool 结果在后端编译成图表，
 * 于是模型全程不需要知道列名、类型或聚合方式。
 *
 * <h3>防串档：双重校验</h3>
 * 取结果时必须同时核对 {@code entCode} 与 {@code conversationId}。
 * 只按 traceId 取，在 traceId 被复用或清理遗漏时（线程复用场景）
 * 会把<b>别的租户、别的会话</b>的数据画出来——这是跨租户泄漏，且不报错。
 *
 * <h3>不是缓存</h3>
 * 是请求作用域的暂存，调用方必须在问答收口时 {@link #clear}；
 * 容量上限只作防御，真被触发说明有路径忘了清理。
 */
@Slf4j
@Component
public class ToolResultRecorder {

    /** 最多保留的 trace 数；超出后按插入顺序淘汰最旧的 */
    private static final int MAX_TRACES = 200;

    private final Map<String, TraceChartContext> contexts = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, TraceChartContext> eldest) {
                    if (size() > MAX_TRACES) {
                        log.warn("图表结果暂存超出上限 {}，淘汰最旧的 traceId={}。"
                                        + "正常路径应在问答结束时 clear——请检查是否有路径漏了",
                                MAX_TRACES, eldest.getKey());
                        return true;
                    }
                    return false;
                }
            });

    /**
     * 记录一次 Tool 的结构化结果。
     * <p>
     * 空结果不记录：画一张空图没有意义，且会让「本轮有无业务数据」的判断失真。
     */
    public void record(String traceId, String entCode, String conversationId,
                       String toolName, List<Map<String, Object>> rows) {
        if (traceId == null || traceId.isBlank() || rows == null || rows.isEmpty()) {
            return;
        }
        this.contexts.computeIfAbsent(traceId,
                        key -> new TraceChartContext(entCode, conversationId, new ArrayList<>()))
                .results()
                .add(new BusinessToolResult(toolName, rows));
    }

    /**
     * 取本轮可用于画图的结果。
     *
     * @return 校验通过时的结果列表；租户或会话对不上时返回<b>空列表</b>（宁可不画，不可画错）
     */
    public List<BusinessToolResult> getResults(String traceId, String entCode, String conversationId) {
        if (traceId == null) {
            return List.of();
        }
        TraceChartContext context = this.contexts.get(traceId);
        if (context == null) {
            return List.of();
        }
        if (!matches(context, entCode, conversationId)) {
            log.warn("图表结果归属校验失败，已拒绝返回：traceId={}, 期望 entCode={}/conversationId={}",
                    traceId, entCode, conversationId);
            return List.of();
        }
        return List.copyOf(context.results());
    }

    /**
     * 清理某轮暂存。必须在问答收口处调用（成功、失败、取消三条路径都要）。
     */
    public void clear(String traceId) {
        if (traceId != null) {
            this.contexts.remove(traceId);
        }
    }

    private boolean matches(TraceChartContext context, String entCode, String conversationId) {
        // 任一侧为空都视为不匹配：宁可不画，也不能把来源不明的数据画给用户
        if (entCode == null || conversationId == null) {
            return false;
        }
        return entCode.equals(context.entCode()) && conversationId.equals(context.conversationId());
    }

    /**
     * 一轮问答的结果上下文。
     */
    record TraceChartContext(String entCode, String conversationId, List<BusinessToolResult> results) {
    }

}
