package com.duduke.erp.entity.dto;

/**
 * 文档解析与向量化的产出。
 *
 * @param chunkCount     成功写入向量库的分片数
 * @param checksumSha256 原文内容的 SHA-256，边解析边计算，用于审计与内容变更判断
 */
public record ManagedDocumentLoadResult(
        Integer chunkCount,
        String checksumSha256) {
}
