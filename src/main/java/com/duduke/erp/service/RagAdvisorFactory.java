package com.duduke.erp.service;

import java.util.List;

import com.duduke.erp.config.RagProperties;
import com.duduke.erp.tenant.TenantContext;

import lombok.RequiredArgsConstructor;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * 构造带租户与知识库隔离的 RAG 检索 Advisor。
 * <p>
 * 召回策略是「先超量、再收紧」：向量库按 {@code topK × 倍数} 超量召回，
 * 随后做资格过滤（版本是否仍有效）与文档分散，最后才截断到 topK。
 * 若不做超采样，被过滤掉的部分会让实际可用结果不足 topK。
 */
@Service
@RequiredArgsConstructor
public class RagAdvisorFactory {

    private final VectorStore vectorStore;

    private final RagProperties ragProperties;

    private final RagContextFormatter contextFormatter;

    private final RagDocumentEligibilityFilter eligibilityFilter;

    private final QueryRewriteService queryRewriteService;

    /** 与 RagTaskExecutorConfig 中的 bean 同名，存在多个 TaskExecutor 时按名字注入 */
    private final TaskExecutor ragTaskExecutor;

    /**
     * 为一个知识库构造 Advisor。
     *
     * @param topK                  最终返回的分片数
     * @param threshold             相似度阈值
     * @param previousUserQuestions 会话内的历史提问（正序），用于补全省略式追问；
     *                              为空表示无历史，此时不做改写
     */
    public RetrievalAugmentationAdvisor create(Long knowledgeBaseId, int topK, double threshold,
                                               List<String> previousUserQuestions) {
        if (topK < 1 || topK > this.ragProperties.getOversampleMax()) {
            throw new IllegalArgumentException(
                    "topK 超出允许范围：1 ~ " + this.ragProperties.getOversampleMax());
        }
        if (threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException("相似度阈值必须在 0 ~ 1 之间");
        }
        String entCode = TenantContext.requireEntCode();

        DocumentRetriever rawRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(this.vectorStore)
                .similarityThreshold(threshold)
                .topK(oversampledTopK(topK))
                .filterExpression(scopeFilter(entCode, knowledgeBaseId))
                .build();

        DocumentRetriever eligibleRetriever = query -> {
            Query scoped = Query.builder()
                    .text(query.text())
                    .history(query.history())
                    .context(query.context())
                    .build();
            List<Document> candidates = rawRetriever.retrieve(scoped);
            return this.eligibilityFilter.filter(knowledgeBaseId, candidates, topK);
        };

        // 检索前改写：把「那上个月呢」这类省略式追问补成可独立检索的查询。
        // 必须放在检索之前——否则已经用那句无意义的短句跑过一次检索了。
        QueryTransformer queryRewriter = query -> Query.builder()
                .text(this.queryRewriteService.rewrite(query.text(), previousUserQuestions))
                .history(query.history())
                .context(query.context())
                .build();

        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(eligibleRetriever)
                .queryTransformers(queryRewriter)
                .queryAugmenter(this.contextFormatter)
                .taskExecutor(this.ragTaskExecutor)
                .build();
    }

    private int oversampledTopK(int topK) {
        int max = this.ragProperties.getOversampleMax();
        return Math.min(max, Math.max(topK, topK * this.ragProperties.getOversampleFactor()));
    }

    /**
     * 租户 + 知识库 + 模型指纹三重过滤。
     * <p>
     * 前两者是隔离要求，缺任一条件都是跨租户/跨库泄露；
     * 第三项保证换模型后旧向量自动退出检索，不会被混用。
     */
    private Filter.Expression scopeFilter(String entCode, Long knowledgeBaseId) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        return builder.and(
                builder.eq("ent_code", entCode),
                builder.and(
                        builder.eq("knowledge_base_id", knowledgeBaseId),
                        builder.eq("embedding_model", this.ragProperties.getEmbeddingModel()))).build();
    }

}
