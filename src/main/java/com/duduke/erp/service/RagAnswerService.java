package com.duduke.erp.service;

import java.util.List;

import com.duduke.erp.config.RagProperties;
import com.duduke.erp.entity.po.KnowledgeBase;
import com.duduke.erp.entity.vo.RagSearchResult;
import com.duduke.erp.tenant.TenantContext;

import lombok.RequiredArgsConstructor;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

/**
 * RAG 检索服务。
 * <p>
 * 提供纯检索能力：不调用大模型、不计费，用于验证入库效果与排查召回质量。
 * 对话链路的 Advisor 装配也在这里，两处共用同一套过滤条件，保证口径一致。
 */
@Service
@RequiredArgsConstructor
public class RagAnswerService {

    private final VectorStore vectorStore;

    private final RagProperties ragProperties;

    private final RagAdvisorFactory ragAdvisorFactory;

    private final RagDocumentEligibilityFilter eligibilityFilter;

    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * 为对话链路装配 Advisor。
     *
     * @param knowledgeBaseId       目标知识库，为空时用默认库
     * @param knowledgeMode         true 走知识问答参数（更宽召回），false 走 auto 模式参数
     * @param previousUserQuestions 会话内历史提问（正序），供检索前改写补全省略式追问
     * @return 已装配好租户 / 知识库 / 模型指纹三重过滤的 Advisor
     */
    public RetrievalAugmentationAdvisor prepareAdvisor(Long knowledgeBaseId, boolean knowledgeMode,
                                                       List<String> previousUserQuestions) {
        KnowledgeBase knowledgeBase = this.knowledgeBaseService.resolveActive(knowledgeBaseId);
        int topK = knowledgeMode
                ? this.ragProperties.getKnowledgeTopK()
                : this.ragProperties.getAutoTopK();
        double threshold = knowledgeMode
                ? this.ragProperties.getKnowledgeSimilarityThreshold()
                : this.ragProperties.getAutoSimilarityThreshold();
        // 模式一并传下去：它决定注入哪套回答指令（严格版 vs 协作版），
        // 而这直接关系到「模型是否会因为资料没提到就丢掉工具查回来的数据」
        return this.ragAdvisorFactory.create(knowledgeBase.getId(), topK, threshold,
                previousUserQuestions, knowledgeMode);
    }

    /**
     * 解析本轮实际使用的知识库，供调用方在消息上记录。
     */
    public KnowledgeBase resolveKnowledgeBase(Long knowledgeBaseId) {
        return this.knowledgeBaseService.resolveActive(knowledgeBaseId);
    }

    private Filter.Expression scopeFilter(Long knowledgeBaseId) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        return builder.and(
                builder.eq("ent_code", TenantContext.requireEntCode()),
                builder.and(
                        builder.eq("knowledge_base_id", knowledgeBaseId),
                        builder.eq("embedding_model", this.ragProperties.getEmbeddingModel()))).build();
    }

    /**
     * 纯向量检索。
     *
     * @param topK 返回条数，为空时取 auto 模式的默认值
     */
    public List<RagSearchResult> search(Long knowledgeBaseId, String query, Integer topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("检索内容不能为空");
        }
        KnowledgeBase knowledgeBase = this.knowledgeBaseService.resolveActive(knowledgeBaseId);
        int effectiveTopK = topK == null ? this.ragProperties.getAutoTopK() : topK;

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(effectiveTopK * this.ragProperties.getOversampleFactor())
                .filterExpression(scopeFilter(knowledgeBase.getId()))
                .build();

        List<Document> candidates = this.vectorStore.similaritySearch(request);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return this.eligibilityFilter.filter(knowledgeBase.getId(), candidates, effectiveTopK).stream()
                .map(this::toResult)
                .toList();
    }

    private RagSearchResult toResult(Document document) {
        Object chunkIndex = document.getMetadata().get("chunk_index");
        return new RagSearchResult(
                document.getText(),
                String.valueOf(document.getMetadata().getOrDefault("source", "")),
                String.valueOf(document.getMetadata().getOrDefault("document_id", "")),
                chunkIndex instanceof Number number ? number.intValue() : null,
                document.getScore());
    }

}
