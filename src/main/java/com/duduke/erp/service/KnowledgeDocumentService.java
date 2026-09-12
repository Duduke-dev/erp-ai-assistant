package com.duduke.erp.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duduke.erp.config.RagProperties;
import com.duduke.erp.entity.dto.DocumentImportRegistration;
import com.duduke.erp.entity.dto.DocumentPromotionResult;
import com.duduke.erp.entity.dto.DocumentVersionKey;
import com.duduke.erp.entity.po.KnowledgeDocument;
import com.duduke.erp.entity.vo.KnowledgeDocumentVO;
import com.duduke.erp.mapper.KnowledgeDocumentMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 知识文档的版本管理与状态机。
 * <p>
 * 状态流转：{@code processing} → {@code ready}（晋级成功）/ {@code failed}（导入异常）
 * / {@code superseded}（被更新版本取代）。检索只认 {@code ready}。
 * 版本号在行锁保护下按「当前最大版本 + 1」计算，避免并发导入产生重复版本号。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentService {

    /** 导入中 */
    public static final String STATUS_PROCESSING = "processing";

    /** 可用；检索只认这个状态 */
    public static final String STATUS_READY = "ready";

    /** 导入失败 */
    public static final String STATUS_FAILED = "failed";

    /** 已被更新的版本取代 */
    public static final String STATUS_SUPERSEDED = "superseded";

    /** 已软删 */
    public static final String STATUS_DELETED = "deleted";

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    private final RagProperties ragProperties;

    /**
     * 登记一次导入，落一条 {@code processing} 版本行。
     *
     * @param documentId 显式指定则替换该文档；为空时按「知识库 + 来源名」复用已有 documentId 形成版本链
     */
    @Transactional
    public DocumentImportRegistration beginImport(Long knowledgeBaseId, String sourceName,
                                                  String contentType, Long fileSize,
                                                  String documentId) {
        String resolvedDocumentId = resolveDocumentId(knowledgeBaseId, sourceName, documentId);
        int nextVersion = nextVersion(resolvedDocumentId);

        KnowledgeDocument po = new KnowledgeDocument();
        po.setKnowledgeBaseId(knowledgeBaseId);
        po.setDocumentId(resolvedDocumentId);
        po.setTitle(sourceName);
        po.setVersion(nextVersion);
        po.setStatus(STATUS_PROCESSING);
        po.setContentType(contentType);
        po.setFileSize(fileSize);
        po.setChunkCount(0);
        LocalDateTime now = LocalDateTime.now();
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        this.knowledgeDocumentMapper.insert(po);

        return new DocumentImportRegistration(resolvedDocumentId, knowledgeBaseId, sourceName, nextVersion);
    }

    /**
     * 晋级为 ready，同时把同文档的旧 ready 版本转为 superseded。
     * <p>
     * 若已存在**更高**版本的 ready（并发或重试导致的迟到版本），本次直接转 superseded 而不覆盖——
     * 否则一次晚完成的旧导入会把新内容顶掉，造成内容回退。
     */
    @Transactional
    public DocumentPromotionResult markReady(DocumentImportRegistration registration,
                                             int chunkCount, String checksumSha256) {
        KnowledgeDocument current = requireProcessing(registration);
        List<KnowledgeDocument> ready = readyVersions(registration.documentId());

        boolean newerReadyExists = ready.stream()
                .anyMatch(doc -> doc.getVersion() > current.getVersion());
        if (newerReadyExists) {
            updateStatus(current.getId(), STATUS_SUPERSEDED, 0);
            log.warn("已存在更高版本的 ready 文档，本次导入标记为 superseded：documentId={}, version={}",
                    registration.documentId(), registration.version());
            return new DocumentPromotionResult(List.of(), 0, STATUS_SUPERSEDED);
        }

        List<Integer> supersededVersions = new ArrayList<>();
        for (KnowledgeDocument doc : ready) {
            updateStatus(doc.getId(), STATUS_SUPERSEDED, doc.getChunkCount());
            supersededVersions.add(doc.getVersion());
        }

        KnowledgeDocument update = new KnowledgeDocument();
        update.setId(current.getId());
        update.setStatus(STATUS_READY);
        update.setChunkCount(chunkCount);
        update.setChecksumSha256(checksumSha256);
        update.setEmbeddingModel(this.ragProperties.getEmbeddingModel());
        update.setErrorMessage("");
        update.setUpdatedAt(LocalDateTime.now());
        this.knowledgeDocumentMapper.updateById(update);

        return new DocumentPromotionResult(supersededVersions, chunkCount, STATUS_READY);
    }

    /**
     * 标记失败。仅当该版本仍处于 {@code processing} 时生效，避免覆盖其他流程写入的状态。
     */
    @Transactional
    public void markFailed(DocumentImportRegistration registration, String errorMessage) {
        KnowledgeDocument current = findVersion(registration.documentId(), registration.version());
        if (current == null || !STATUS_PROCESSING.equals(current.getStatus())) {
            return;
        }
        KnowledgeDocument update = new KnowledgeDocument();
        update.setId(current.getId());
        update.setStatus(STATUS_FAILED);
        update.setChunkCount(0);
        update.setErrorMessage(truncate(errorMessage, 1000));
        update.setUpdatedAt(LocalDateTime.now());
        this.knowledgeDocumentMapper.updateById(update);
    }

    /**
     * 软删一个文档的全部版本。
     *
     * @return 被删除的版本号列表，供调用方清理对应向量
     */
    @Transactional
    public List<Integer> deleteDocument(String documentId) {
        List<KnowledgeDocument> versions = allVersions(documentId);
        if (versions.isEmpty()) {
            throw new IllegalArgumentException("文档不存在或不属于当前租户");
        }
        List<Integer> removed = new ArrayList<>();
        for (KnowledgeDocument doc : versions) {
            if (STATUS_DELETED.equals(doc.getStatus())) {
                continue;
            }
            updateStatus(doc.getId(), STATUS_DELETED, doc.getChunkCount());
            removed.add(doc.getVersion());
        }
        return removed;
    }

    /**
     * 列出知识库下的文档，每个 documentId 只返回最新版本。
     */
    public List<KnowledgeDocumentVO> listDocuments(Long knowledgeBaseId) {
        List<KnowledgeDocument> rows = this.knowledgeDocumentMapper.selectList(
                Wrappers.<KnowledgeDocument>lambdaQuery()
                        .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                        .ne(KnowledgeDocument::getStatus, STATUS_DELETED)
                        .orderByDesc(KnowledgeDocument::getId));

        Map<String, KnowledgeDocument> latestPerDocument = new HashMap<>();
        for (KnowledgeDocument row : rows) {
            latestPerDocument.putIfAbsent(row.getDocumentId(), row);
        }
        return latestPerDocument.values().stream().map(this::toVO).toList();
    }

    /**
     * 从版本键中筛出「当前仍为 ready 且模型指纹匹配」的项，返回 版本键 → 来源名。
     * <p>
     * 供检索后的资格过滤使用：向量库可能残留已 superseded 或旧模型生成的向量，
     * 必须回到数据库确认其所属版本现在是否仍然有效。
     */
    public Map<DocumentVersionKey, String> findReadyVersionSources(List<DocumentVersionKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        List<String> documentIds = keys.stream().map(DocumentVersionKey::documentId).distinct().toList();
        List<KnowledgeDocument> ready = this.knowledgeDocumentMapper.selectList(
                Wrappers.<KnowledgeDocument>lambdaQuery()
                        .in(KnowledgeDocument::getDocumentId, documentIds)
                        .eq(KnowledgeDocument::getStatus, STATUS_READY)
                        .eq(KnowledgeDocument::getEmbeddingModel, this.ragProperties.getEmbeddingModel()));

        Map<DocumentVersionKey, String> filtered = new HashMap<>();
        for (KnowledgeDocument doc : ready) {
            DocumentVersionKey key = new DocumentVersionKey(doc.getDocumentId(), doc.getVersion());
            if (keys.contains(key)) {
                filtered.put(key, doc.getTitle());
            }
        }
        return filtered;
    }

    private String resolveDocumentId(Long knowledgeBaseId, String sourceName, String explicitDocumentId) {
        if (StringUtils.hasText(explicitDocumentId)) {
            List<KnowledgeDocument> versions = allVersions(explicitDocumentId);
            if (versions.isEmpty()) {
                throw new IllegalArgumentException("文档不存在或不属于当前租户");
            }
            if (!knowledgeBaseId.equals(versions.get(0).getKnowledgeBaseId())) {
                throw new IllegalArgumentException("文档不属于指定知识库");
            }
            return explicitDocumentId;
        }

        List<KnowledgeDocument> sameSource = this.knowledgeDocumentMapper.selectList(
                Wrappers.<KnowledgeDocument>lambdaQuery()
                        .eq(KnowledgeDocument::getKnowledgeBaseId, knowledgeBaseId)
                        .eq(KnowledgeDocument::getTitle, sourceName)
                        .orderByDesc(KnowledgeDocument::getVersion));
        if (!sameSource.isEmpty()) {
            return sameSource.get(0).getDocumentId();
        }
        return UUID.randomUUID().toString();
    }

    private int nextVersion(String documentId) {
        return lockVersions(documentId).stream()
                .mapToInt(KnowledgeDocument::getVersion)
                .max()
                .orElse(0) + 1;
    }

    /** 加行锁读取版本链，供版本号计算使用 */
    private List<KnowledgeDocument> lockVersions(String documentId) {
        return this.knowledgeDocumentMapper.selectList(
                Wrappers.<KnowledgeDocument>lambdaQuery()
                        .eq(KnowledgeDocument::getDocumentId, documentId)
                        .orderByAsc(KnowledgeDocument::getVersion)
                        .last("FOR UPDATE"));
    }

    private List<KnowledgeDocument> allVersions(String documentId) {
        return this.knowledgeDocumentMapper.selectList(
                Wrappers.<KnowledgeDocument>lambdaQuery()
                        .eq(KnowledgeDocument::getDocumentId, documentId)
                        .orderByAsc(KnowledgeDocument::getVersion));
    }

    private List<KnowledgeDocument> readyVersions(String documentId) {
        return this.knowledgeDocumentMapper.selectList(
                Wrappers.<KnowledgeDocument>lambdaQuery()
                        .eq(KnowledgeDocument::getDocumentId, documentId)
                        .eq(KnowledgeDocument::getStatus, STATUS_READY));
    }

    private KnowledgeDocument findVersion(String documentId, Integer version) {
        return this.knowledgeDocumentMapper.selectOne(
                Wrappers.<KnowledgeDocument>lambdaQuery()
                        .eq(KnowledgeDocument::getDocumentId, documentId)
                        .eq(KnowledgeDocument::getVersion, version));
    }

    private KnowledgeDocument requireProcessing(DocumentImportRegistration registration) {
        KnowledgeDocument doc = findVersion(registration.documentId(), registration.version());
        if (doc == null) {
            throw new IllegalStateException("导入登记不存在：" + registration.documentId());
        }
        if (!STATUS_PROCESSING.equals(doc.getStatus())) {
            throw new IllegalStateException("导入登记状态已变更：" + doc.getStatus());
        }
        return doc;
    }

    private void updateStatus(Long id, String status, Integer chunkCount) {
        KnowledgeDocument update = new KnowledgeDocument();
        update.setId(id);
        update.setStatus(status);
        update.setChunkCount(chunkCount == null ? 0 : chunkCount);
        update.setUpdatedAt(LocalDateTime.now());
        this.knowledgeDocumentMapper.updateById(update);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private KnowledgeDocumentVO toVO(KnowledgeDocument po) {
        boolean requiresReindex = STATUS_READY.equals(po.getStatus())
                && po.getEmbeddingModel() != null
                && !po.getEmbeddingModel().equals(this.ragProperties.getEmbeddingModel());
        return new KnowledgeDocumentVO(
                po.getId(),
                po.getKnowledgeBaseId(),
                po.getDocumentId(),
                po.getTitle(),
                po.getVersion(),
                po.getStatus(),
                po.getFileSize(),
                po.getContentType(),
                po.getChunkCount(),
                po.getEmbeddingModel(),
                requiresReindex,
                po.getErrorMessage(),
                po.getCreatedAt(),
                po.getUpdatedAt());
    }

}
