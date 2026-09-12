package com.duduke.erp.entity.dto;

/**
 * 文档版本键。
 * <p>
 * 用于把向量元数据（{@code document_id} + {@code document_version}）映射回数据库中的版本行，
 * 以判断该向量所属版本当前是否仍为 ready。
 */
public record DocumentVersionKey(
        String documentId,
        Integer version) {
}
