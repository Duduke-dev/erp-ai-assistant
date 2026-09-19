package com.duduke.erp.service.tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.duduke.erp.entity.vo.RagSearchResult;
import com.duduke.erp.service.RagAnswerService;
import com.duduke.erp.service.RagRecallRecorder;
import com.duduke.erp.service.tool.trace.ToolTraceKeys;

import lombok.RequiredArgsConstructor;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 知识库检索 Tool —— 让模型自己决定「这轮要不要查资料」。
 *
 * <h3>为什么做成 Tool 而不是 Advisor</h3>
 * 原先 RAG 是 Advisor 式：只要挂上就<b>每轮强制检索</b>，与问题内容无关。三个后果——
 * 闲聊也会触发 embedding 调用（浪费）；无条件往 prompt 塞段落，弱相关时反而干扰模型
 * 使用工具结果（2026-09-19 实际发生过：模型丢掉了已经查回的业务数据，答「现有资料无法回答」）；
 * 而且只能检索一次，做不到「粗查 → 据结果细查」。
 *
 * <h3>引用怎么来的</h3>
 * 召回结果除了拼成文本给模型，还会写入 {@link RagRecallRecorder}（按 traceId 暂存），
 * 收口时与 Advisor 那条链路的结果合并，供 {@code RagCitationService} 校验引用。
 * <b>编号必须跨多次调用全局递增</b>——模型可能分两次检索，若各自从 [1] 起编，
 * 引用会指向错误内容且不报错。所以这里用 {@code record} 返回的起始编号来编号。
 *
 * <h3>知识库从哪来</h3>
 * 工具签名里只有业务参数，请求体的知识库选择经 {@code ToolContext} 带入
 * （见 {@link ToolTraceKeys#KNOWLEDGE_BASE_ID}）；缺失时检索侧回落到默认库。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeSearchTool implements BusinessTool {

    /** 默认返回片段数 */
    private static final int DEFAULT_TOP_K = 5;

    /** 上限：给模型调速空间，但不能让它一次拉太多把上下文撑爆 */
    private static final int MAX_TOP_K = 10;

    private static final String UNKNOWN_SOURCE = "未知来源";

    private final RagAnswerService ragAnswerService;

    private final RagRecallRecorder recallRecorder;

    @Tool(name = ToolNames.SEARCH_KNOWLEDGE_BASE,
          description = "在企业知识库中检索资料，返回带 [编号] 的文档片段。"
                  + "涉及制度、流程、规范、操作说明等文档内容时调用它；"
                  + "检索不到时会明确说明，此时不要凭印象作答。")
    public String searchKnowledgeBase(
            @ToolParam(description = "检索词，用一句话描述要找的内容，例如「退货流程」「AQL 抽样标准」")
            String query,
            @ToolParam(required = false,
                       description = "返回片段数，默认 " + DEFAULT_TOP_K + "，最多 " + MAX_TOP_K)
            Integer topK,
            ToolContext toolContext) {
        Long knowledgeBaseId = knowledgeBaseIdOf(toolContext);
        int limit = (topK == null || topK < 1) ? DEFAULT_TOP_K : Math.min(topK, MAX_TOP_K);

        List<RagSearchResult> results = this.ragAnswerService.search(knowledgeBaseId, query, limit);
        if (results.isEmpty()) {
            // 明确回「没有」，而不是空串：空串让模型无从判断，容易转而凭印象编
            return "知识库中没有检索到与该问题相关的内容。";
        }

        // 先登记再编号：起始编号由已累计的数量决定，保证多次检索之间不撞号
        int startIndex = this.recallRecorder.record(traceIdOf(toolContext), toDocuments(results));

        StringBuilder context = new StringBuilder();
        for (int index = 0; index < results.size(); index++) {
            RagSearchResult result = results.get(index);
            context.append('[').append(startIndex + index).append("] 来源：")
                    .append(result.source() == null ? UNKNOWN_SOURCE : result.source())
                    .append('\n')
                    .append(result.content())
                    .append("\n\n");
        }
        return context.toString().trim();
    }

    /**
     * 转成 {@link Document} 以便复用引用校验。
     * <p>
     * 只带 {@code source}：引用校验按<b>列表位置</b>匹配编号，不依赖额外元数据。
     */
    private List<Document> toDocuments(List<RagSearchResult> results) {
        List<Document> documents = new ArrayList<>(results.size());
        for (RagSearchResult result : results) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", result.source() == null ? UNKNOWN_SOURCE : result.source());
            documents.add(new Document(result.content(), metadata));
        }
        return documents;
    }

    /** 从 ToolContext 取知识库 ID；缺失或类型不符都返回 null（检索侧会回落到默认库） */
    private Long knowledgeBaseIdOf(ToolContext toolContext) {
        return longValueOf(toolContext, ToolTraceKeys.KNOWLEDGE_BASE_ID);
    }

    private String traceIdOf(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(ToolTraceKeys.TRACE_ID);
        return value == null ? null : String.valueOf(value);
    }

    private Long longValueOf(ToolContext toolContext, String key) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(key);
        return value instanceof Number number ? number.longValue() : null;
    }
}
