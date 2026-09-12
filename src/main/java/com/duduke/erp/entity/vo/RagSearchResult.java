package com.duduke.erp.entity.vo;

/**
 * 纯向量检索结果（不经过大模型，不计费）。
 * <p>
 * 用于检索效果自查：可以看出某个问题实际召回了哪些分片、相似度多少，
 * 是调参与排查召回质量的主要手段。
 */
public record RagSearchResult(
        String content,
        String source,
        Integer chunkIndex,
        Double score) {
}
