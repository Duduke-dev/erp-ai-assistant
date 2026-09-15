package com.duduke.erp.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.duduke.erp.common.exception.BusinessException;
import com.duduke.erp.config.MessagingProperties;
import com.duduke.erp.entity.dto.DocumentImportRegistration;
import com.duduke.erp.entity.dto.DocumentParseMessage;
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

    private final ObjectStorageService objectStorageService;

    private final DocumentParsePublisher documentParsePublisher;

    private final MessagingProperties messagingProperties;

    /**
     * 导入一个文档：按 {@code app.mq.async-enabled} 分流到异步或同步链路。
     * <p>
     * <b>为什么分流收在这一层而不是 Controller</b>：开关属于编排决策，
     * 放在 Controller 就要把 {@link MessagingProperties} 暴露给接入层，
     * 任何绕过 Controller 的调用方（定时任务、管理端）都会拿到不同的行为。
     *
     * @param content 已读入内存的原件；异步链路要把它写进对象存储，
     *                同步链路也直接消费它，因此统一以字节数组传入
     */
    public void importDocument(Long knowledgeBaseId, String fileName, String contentType,
                               byte[] content, String documentId) {
        if (this.messagingProperties.isAsyncEnabled()) {
            importFileAsync(knowledgeBaseId, fileName, contentType, content, documentId);
            return;
        }
        importFile(knowledgeBaseId, fileName, contentType,
                content == null ? 0 : content.length,
                new ByteArrayInputStream(content), documentId);
    }

    /**
     * 异步导入：只做「登记版本 → 存原件 → 投递消息」，解析交给消费侧。
     * <p>
     * <b>关键点：{@code beginImport} 必须在这一段做完</b>。
     * 版本号与 documentId 都由它产生，而消息要带着它们才能定位版本；
     * 若留给消费侧再调一次 {@link #importFile}，就会引入第二次
     * {@code beginImport} —— 同一个文件多出一个版本，能编译、能跑、结果是错的。
     *
     * @throws BusinessException 存原件或投递失败时抛出；此时版本已登记为
     *                           {@code processing}，必须就地标记 failed，
     *                           否则这条文档永远停在"处理中"且没有任何报错
     */
    public void importFileAsync(Long knowledgeBaseId, String fileName, String contentType,
                                byte[] content, String documentId) {
        validateSupportedFileFormat(fileName);
        if (content == null || content.length == 0) {
            throw new BusinessException("上传内容不能为空");
        }
        KnowledgeBase knowledgeBase = this.knowledgeBaseService.resolveActive(knowledgeBaseId);
        String entCode = TenantContext.requireEntCode();

        DocumentImportRegistration registration = this.knowledgeDocumentService.beginImport(
                knowledgeBase.getId(), fileName, contentType, (long) content.length, documentId);
        ManagedDocumentMetadata metadata = new ManagedDocumentMetadata(
                entCode, knowledgeBase.getId(), registration.documentId(),
                registration.version(), fileName, contentType);

        try {
            // 先存原件再投递：消息里带着对象键，消费侧取不到原件就是"上传成功但永远解析不了"
            String objectKey = this.objectStorageService.put(
                    this.objectStorageService.buildKey(entCode, fileName), content, contentType);
            // 键要回写进库：只在消息里的话，删除文档时清不掉原件，死信排查也反查不回来
            this.knowledgeDocumentService.attachObject(registration.documentId(), registration.version(),
                    this.objectStorageService.bucketName(), objectKey);
            this.knowledgeDocumentService.markStage(registration.documentId(), registration.version(),
                    KnowledgeDocumentService.STAGE_QUEUED);
            this.documentParsePublisher.publish(new DocumentParseMessage(
                    entCode, registration.documentId(), knowledgeBase.getId(),
                    objectKey, fileName, contentType, registration.version()));
            log.info("解析任务已登记：documentId={}, version={}, objectKey={}",
                    registration.documentId(), registration.version(), objectKey);
        }
        catch (RuntimeException e) {
            throw completeFailedImport(registration, metadata, e);
        }
    }

    /**
     * 导入一个文档流（同步链路，也是异步被关闭时的降级路径）。
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
        parseAndPromote(metadata, inputStream);
    }

    /**
     * 解析并晋级一个<b>已登记</b>的版本：解析入库 → 标记 ready → 清理被取代的向量。
     * <p>
     * 异步链路的消费侧走这里：登记已经在上传线程做过了，消费侧<b>不能也不该</b>再登记一次。
     * <p>
     * 登记凭据由 {@code metadata} 派生而非单独入参——两者字段本就重叠，
     * 分开传就要靠调用方保证一致，多一份真相就多一处静默错位。
     *
     * @param metadata     版本定位四元组 + 来源信息
     * @param inputStream  原件内容流
     */
    public void parseAndPromote(ManagedDocumentMetadata metadata, InputStream inputStream) {
        DocumentImportRegistration registration = registrationOf(metadata);
        this.knowledgeDocumentService.markStage(
                metadata.documentId(), metadata.version(), KnowledgeDocumentService.STAGE_PARSING);
        try {
            ManagedDocumentLoadResult loadResult = this.documentLoaderService.loadAndStore(
                    inputStream, metadata,
                    () -> this.knowledgeDocumentService.markStage(
                            metadata.documentId(), metadata.version(),
                            KnowledgeDocumentService.STAGE_EMBEDDING));
            DocumentPromotionResult promotion = this.knowledgeDocumentService.markReady(
                    registration, loadResult.chunkCount(), loadResult.checksumSha256());
            cleanupSupersededVersions(metadata, promotion.supersededVersions());
            log.info("文档导入完成：{}，分片 {}，状态 {}",
                    metadata.sourceName(), loadResult.chunkCount(), promotion.status());
        }
        catch (RuntimeException e) {
            throw completeFailedImport(registration, metadata, e);
        }
    }

    /**
     * 删除文档及其全部版本的向量。
     */
    public void deleteDocument(Long knowledgeBaseId, String documentId) {
        KnowledgeBase knowledgeBase = this.knowledgeBaseService.resolveActive(knowledgeBaseId);
        // 必须在软删之前取：记录一旦置为 deleted，就再也查不到原件键了
        List<String> objectKeys = this.knowledgeDocumentService.objectKeysOf(documentId);
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
        for (String objectKey : objectKeys) {
            try {
                this.objectStorageService.remove(objectKey);
            } catch (RuntimeException e) {
                // 同样尽力：对象残留不影响检索与状态，但要在日志里留痕
                log.warn("清理对象存储原件失败：objectKey={}", objectKey, e);
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

    /**
     * 由 {@code metadata} 派生登记凭据。
     * <p>
     * {@code metadata} 的字段完整覆盖 {@code registration} 所需的四元组，
     * 因此二者可以互相推导；固定由 metadata 派生，避免调用方传进不一致的两份。
     */
    private static DocumentImportRegistration registrationOf(ManagedDocumentMetadata metadata) {
        return new DocumentImportRegistration(
                metadata.documentId(), metadata.knowledgeBaseId(),
                metadata.sourceName(), metadata.version());
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
