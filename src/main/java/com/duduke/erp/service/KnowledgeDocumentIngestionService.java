package com.duduke.erp.service;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.duduke.erp.common.exception.BusinessException;
import com.duduke.erp.entity.dto.DocumentImportRegistration;
import com.duduke.erp.entity.dto.DocumentPromotionResult;
import com.duduke.erp.entity.dto.ManagedDocumentLoadResult;
import com.duduke.erp.entity.dto.ManagedDocumentMetadata;
import com.duduke.erp.entity.po.KnowledgeBase;
import com.duduke.erp.tenant.TenantContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

/**
 * 文档导入编排。
 * <p>
 * 负责把「登记版本 → 解析入库 → 晋级状态 → 清理旧向量」串起来，并统一处理失败补偿。
 * 具体解析与向量化在 {@link DocumentLoaderService}，版本状态在 {@link KnowledgeDocumentService}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentIngestionService {

    /** 支持的文档扩展名。在登记版本之前校验，避免非法文件留下导入记录。 */
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "txt", "md", "html", "htm", "rtf", "csv");

    private final KnowledgeBaseService knowledgeBaseService;

    private final KnowledgeDocumentService knowledgeDocumentService;

    private final DocumentLoaderService documentLoaderService;

    /**
     * 导入一个文档流。
     *
     * @param knowledgeBaseId 目标知识库，为空时落到默认库
     * @param fileName        原始文件名，同时作为来源标识
     * @param documentId      指定则替换该文档（新版本），为空则按来源名自动归并
     */
    public void importFile(Long knowledgeBaseId, String fileName, String contentType,
                           long size, InputStream inputStream, String documentId) {
        validateSupportedFileFormat(fileName);
        KnowledgeBase knowledgeBase = this.knowledgeBaseService.resolveActive(knowledgeBaseId);
        String entCode = TenantContext.requireEntCode();

        DocumentImportRegistration registration = this.knowledgeDocumentService.beginImport(
                knowledgeBase.getId(), fileName, contentType, size, documentId);
        ManagedDocumentMetadata metadata = new ManagedDocumentMetadata(
                entCode, knowledgeBase.getId(), registration.documentId(),
                registration.version(), fileName, contentType);

        try {
            ManagedDocumentLoadResult loadResult =
                    this.documentLoaderService.loadAndStore(inputStream, metadata);
            DocumentPromotionResult promotion = this.knowledgeDocumentService.markReady(
                    registration, loadResult.chunkCount(), loadResult.checksumSha256());
            cleanupSupersededVersions(metadata, promotion.supersededVersions());
            log.info("文档导入完成：{}，分片 {}，状态 {}",
                    fileName, loadResult.chunkCount(), promotion.status());
        } catch (RuntimeException e) {
            throw completeFailedImport(registration, metadata, e);
        }
    }

    /**
     * 删除文档及其全部版本的向量。
     */
    public void deleteDocument(Long knowledgeBaseId, String documentId) {
        KnowledgeBase knowledgeBase = this.knowledgeBaseService.resolveActive(knowledgeBaseId);
        List<Integer> removedVersions = this.knowledgeDocumentService.deleteDocument(documentId);
        String entCode = TenantContext.requireEntCode();

        for (Integer version : removedVersions) {
            ManagedDocumentMetadata metadata = new ManagedDocumentMetadata(
                    entCode, knowledgeBase.getId(), documentId, version, null, null);
            try {
                this.documentLoaderService.deleteVersion(metadata);
            } catch (RuntimeException e) {
                // 尽力语义：向量残留会被资格过滤挡掉，不值得让删除操作失败
                log.warn("清理文档向量失败：documentId={}, version={}", documentId, version, e);
            }
        }
    }

    /**
     * 校验文件格式。放在登记版本之前，避免非法文件产生导入记录。
     */
    void validateSupportedFileFormat(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException("文件名不能为空");
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            throw new BusinessException("文件缺少扩展名，无法判断格式：" + fileName);
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new BusinessException("不支持的文件格式：." + extension
                    + "，支持 " + String.join(" / ", SUPPORTED_EXTENSIONS.stream().sorted().toList()));
        }
    }

    private void cleanupSupersededVersions(ManagedDocumentMetadata metadata, List<Integer> supersededVersions) {
        for (Integer version : supersededVersions) {
            ManagedDocumentMetadata stale = new ManagedDocumentMetadata(
                    metadata.entCode(), metadata.knowledgeBaseId(),
                    metadata.documentId(), version, metadata.sourceName(), metadata.contentType());
            try {
                this.documentLoaderService.deleteVersion(stale);
            } catch (RuntimeException e) {
                // 旧版本向量残留不影响正确性：检索时的资格过滤会将其排除
                log.warn("清理被取代版本的向量失败：documentId={}, version={}",
                        metadata.documentId(), version, e);
            }
        }
    }

    /**
     * 失败补偿：清理本版本已写入的向量，把版本标记为 failed，再抛出统一的安全文案。
     * <p>
     * 状态标记本身失败时用 {@code addSuppressed} 挂上，不覆盖原始根因。
     */
    private BusinessException completeFailedImport(DocumentImportRegistration registration,
                                                   ManagedDocumentMetadata metadata,
                                                   RuntimeException cause) {
        try {
            this.documentLoaderService.deleteVersion(metadata);
        } catch (RuntimeException cleanupFailure) {
            cause.addSuppressed(cleanupFailure);
        }
        try {
            this.knowledgeDocumentService.markFailed(registration, cause.getMessage());
        } catch (RuntimeException statusFailure) {
            cause.addSuppressed(statusFailure);
        }
        log.error("文档导入失败：documentId={}, version={}",
                registration.documentId(), registration.version(), cause);
        return new BusinessException(500, "知识文档导入失败，请稍后重试", cause);
    }

}
