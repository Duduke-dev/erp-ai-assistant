package com.duduke.erp.entity.dto;

/**
 * 一次文档导入的登记凭据。
 * <p>
 * 在事务内登记 {@code processing} 版本时生成，后续成功晋级或失败补偿都靠它定位版本。
 *
 * @param documentId      稳定文档 ID
 * @param knowledgeBaseId 知识库主键
 * @param sourceName      来源文件名
 * @param version         本次导入产生的版本号
 */
public record DocumentImportRegistration(
        String documentId,
        Long knowledgeBaseId,
        String sourceName,
        Integer version) {
}
