package com.duduke.erp.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 检索与分块参数。
 * <p>
 * 集中配置而非散落为常量：更换嵌入模型时，需要同步重标定的相似度阈值、
 * 向量维度、分块尺度都在同一处，便于整体调整与对比实验。
 */
@Data
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    /** auto 模式的召回条数 */
    private int autoTopK = 5;

    /** auto 模式的相似度阈值 */
    private double autoSimilarityThreshold = 0.5;

    /** knowledge 模式的召回条数 */
    private int knowledgeTopK = 8;

    /** knowledge 模式的相似度阈值 */
    private double knowledgeSimilarityThreshold = 0.25;

    /**
     * 检索查询改写的实现方式。
     * <p>
     * {@code rule}：只用规则式（补全省略式追问「那上个月呢」），确定、零成本、不引入新失败点；
     * {@code model}：在规则式补全之后再用模型润色，让口语化问题（「上次那个料还够不够」）
     * 更贴近知识库语料的表述，提升召回。
     * <p>
     * <b>默认 rule</b>：模型式会多一次 LLM 调用（延迟 + token），
     * 而且改写幅度一旦过大，检索会偏离用户原意且**看起来完全正常、极难发现**——
     * 需要对照实验（同为 rule / model，比 Recall@5 与 MRR@5）确认收益后再切换。
     */
    private String rewriteMode = "rule";

    /** 过采样倍数：先取 topK × factor，再做资格过滤与去重分散 */
    private int oversampleFactor = 3;

    /** 过采样上限，同时是 topK 的硬上限 */
    private int oversampleMax = 24;

    /**
     * 嵌入模型指纹。
     * <p>
     * 写入每个向量的元数据，并在检索时强制比对：更换模型后旧向量自动失效，
     * 避免不同语义空间的向量混用（即使维度相同也不能混）。
     */
    private String embeddingModel = "dashscope/text-embedding-v4@1024";

    /** 向量维度，必须与 pgvector 表结构严格一致 */
    private int embeddingDimensions = 1024;

    /** 单条分片的 token 上限，用于二次校验 */
    private int embeddingMaxTokens = 8192;

    /** 单文件解析出的最大字符数，防止超大文档耗尽内存 */
    private int maxExtractedChars = 5_000_000;

    /** 分块目标 token 数 */
    private int chunkSize = 600;

    /** 单块最小字符数，低于此值会与相邻块合并 */
    private int minChunkSizeChars = 200;

    /** 低于此 token 数的块不参与嵌入 */
    private int minChunkLengthToEmbed = 50;

    /** 单文档最大分块数，防止异常文档产生海量向量 */
    private int maxNumChunks = 20_000;

    /**
     * 批量写入向量库的批大小。
     * <p>
     * <b>它不是性能调优参数，而是由 embedding provider 的批量上限决定</b>：
     * DashScope 单次最多 10 条文本，超出直接 400
     * （{@code InvalidParameter: batch size is invalid, it should not be larger than 10}）；
     * OpenAI 可到 2048。**换 provider 必须同步调整。**
     * <p>
     * 也不能指望下游再分一次：Spring AI 内置的批处理策略按 <b>token 数</b>切分，
     * 而这里的限制是 <b>条数</b>——两个维度不同，按 token 分批不能保证条数合规。
     * 之前配成 100 一直没暴露，只是因为评测用的文档都很小（分片数不足 10），
     * 属于侥幸，不是正确。
     */
    private int vectorWriteBatchSize = 10;

}
