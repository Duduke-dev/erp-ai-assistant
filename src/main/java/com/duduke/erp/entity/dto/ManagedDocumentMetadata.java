package com.duduke.erp.entity.dto;

/**
 * 文档导入过程中携带的身份信息，用于写入向量元数据与清理向量。
 * <p>
 * 四个字段构成向量的定位四元组：{@code entCode + knowledgeBaseId + documentId + version}。
 * 删除必须按这四项精确匹配——只按文件名删会误伤其他租户或其他知识库的同名文档。
 *
 * @param entCode         租户标识
 * @param knowledgeBaseId 知识库主键
 * @param documentId      稳定文档 ID
 * @param version         版本号
 * @param sourceName      来源文件名
 * @param contentType     内容类型
 */
public record ManagedDocumentMetadata(
        String entCode,
        Long knowledgeBaseId,
        String documentId,
        Integer version,
        String sourceName,
        String contentType) {
}
