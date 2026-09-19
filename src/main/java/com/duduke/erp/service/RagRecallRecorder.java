package com.duduke.erp.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * 记录「本轮由知识检索 Tool 召回了哪些文档」。
 *
 * <h3>为什么需要它</h3>
 * 引用（citations）原先只从 Advisor 那条链路取：{@code RetrievalAugmentationAdvisor}
 * 把文档写进 {@code ChatResponse} 元数据，兜底再读 {@code RagContextFormatter} 的暂存。
 * 但 2026-09-20 起 auto 模式**不再挂 Advisor**，改为让模型调用
 * {@code search_knowledge_base} Tool 按需检索 —— 于是上述两条来源都为空，
 * 引用列表会空掉（回答里却仍有 [编号]，前后不一致）。
 *
 * <h3>为什么用 traceId 索引而不是 ThreadLocal</h3>
 * 工具执行线程与流式收口线程**不是同一个**（收口在 SSE / Reactor 线程上），
 * ThreadLocal 跨不过去。这与 {@code RagContextFormatter} 当初的取舍不同：
 * 那个类恰好与检索同线程，这里不行。
 *
 * <h3>编号必须全局递增</h3>
 * 模型可能分多次检索（粗查 → 细查）。若每轮工具调用都从 [1] 开始编号，
 * 第二次的 [1] 会与第一次的 [1] 撞号，而引用校验只按「在列表中的位置」匹配，
 * 结果是引用指向错误内容 —— 且不报错。所以这里记录时返回**起始编号**，
 * 由工具据此编号，保证与合并后的列表位置一致。
 */
@Component
public class RagRecallRecorder {

    /** traceId → 本轮累计召回的文档（按召回顺序，跨多次工具调用累积） */
    private final Map<String, List<Document>> byTrace = new ConcurrentHashMap<>();

    /**
     * 追加一批召回文档。
     *
     * @return 这批文档的**起始编号**（1-based），供调用方在返回给模型的文本里编号
     */
    public synchronized int record(String traceId, List<Document> documents) {
        if (traceId == null || documents == null || documents.isEmpty()) {
            return 1;
        }
        List<Document> all = this.byTrace.computeIfAbsent(traceId, key -> new ArrayList<>());
        int startIndex = all.size() + 1;
        all.addAll(documents);
        return startIndex;
    }

    /** 取本轮全部召回文档；未记录过时返回空列表 */
    public List<Document> getAll(String traceId) {
        if (traceId == null) {
            return List.of();
        }
        List<Document> documents = this.byTrace.get(traceId);
        return documents == null ? List.of() : List.copyOf(documents);
    }

    /**
     * 清理本轮记录。
     * <p>
     * 必须在轮次结束时调用——不然 Map 会随会话无限增长（这是内存泄漏，
     * 而且不会报错，只会慢慢吃掉堆）。
     */
    public synchronized void clear(String traceId) {
        if (traceId != null) {
            this.byTrace.remove(traceId);
        }
    }

    /** 供诊断：当前暂存的轮次数 */
    public int size() {
        return this.byTrace.size();
    }
}
