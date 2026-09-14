package com.duduke.erp.service.tool.trace;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

/**
 * 按 traceId 聚合一轮问答内的 Tool 调用明细。
 * <p>
 * 存在的意义是<b>跨 Tool 聚合</b>：一条 {@code tool_call_log} 记一次调用，
 * 而链路层关心的是「这一轮总共调了哪些 Tool、结果如何」——
 * 用于写 {@code chat_message.tool_calls}，以及 M4 判断「本轮有没有产生业务数据」。
 * <p>
 * <b>不是缓存，是请求作用域的暂存</b>：调用方必须在问答结束时 {@link #clearTrace}，
 * 否则线程复用会让下一轮读到上一轮的调用明细（属于引用串档）。
 * 容量上限只作防御——真被触发说明有路径忘了清理。
 */
@Slf4j
@Component
public class ToolCallRecorder {

    /** 最多保留的 trace 数；超出后按插入顺序淘汰最旧的 */
    private static final int MAX_TRACES = 500;

    private final Map<String, List<ToolCallRecord>> records = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<ToolCallRecord>> eldest) {
                    if (size() > MAX_TRACES) {
                        log.warn("Tool 调用记录超出上限 {}，淘汰最旧的 traceId={}。"
                                + "正常路径应在问答结束时 clearTrace——请检查是否有路径漏了",
                                MAX_TRACES, eldest.getKey());
                        return true;
                    }
                    return false;
                }
            });

    /** 生成新的链路 ID */
    public static String createTraceId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 记录一次 Tool 调用。
     *
     * @param traceId 链路 ID；为空时直接忽略（没有链路归属的调用不参与聚合）
     */
    public void record(String traceId, ToolCallRecord record) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        this.records.computeIfAbsent(traceId, key -> new CopyOnWriteArrayList<>()).add(record);
    }

    /**
     * 取某轮的全部调用明细。
     *
     * @return 不可变副本；无记录时返回空列表
     */
    public List<ToolCallRecord> getResults(String traceId) {
        if (traceId == null) {
            return List.of();
        }
        List<ToolCallRecord> found = this.records.get(traceId);
        return found == null ? List.of() : List.copyOf(found);
    }

    /** 本轮是否产生过 Tool 调用（供 M4 判断「有没有业务数据」） */
    public boolean hasResults(String traceId) {
        return !getResults(traceId).isEmpty();
    }

    /**
     * 清理某轮的暂存。<b>必须在问答收口处调用</b>，成功、失败、被取消三条路径都要。
     */
    public void clearTrace(String traceId) {
        if (traceId != null) {
            this.records.remove(traceId);
        }
    }

}
