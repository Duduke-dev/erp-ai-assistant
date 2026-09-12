package com.duduke.erp.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.duduke.erp.config.RagProperties;
import com.duduke.erp.entity.dto.DocumentVersionKey;
import com.duduke.erp.tenant.TenantContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * 检索结果的资格过滤。
 * <p>
 * 向量库里可能残留已 superseded、已删除或由旧模型生成的向量（清理是"尽力语义"），
 * 因此召回后必须回到数据库确认：该向量所属版本现在是否仍为 ready、是否由当前模型生成。
 * 同时做租户与知识库范围的二次校验——向量库没有行级权限，漏掉任一条件就是跨租户泄露。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagDocumentEligibilityFilter {

    private final KnowledgeDocumentService knowledgeDocumentService;

    private final RagProperties ragProperties;

    /**
     * 过滤并按文档分散，最后截断到 topK。
     */
    public List<Document> filter(Long knowledgeBaseId, List<Document> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        String entCode = TenantContext.requireEntCode();

        List<DocumentVersionKey> versionKeys = new ArrayList<>();
        for (Document document : candidates) {
            if (isCandidateUsable(document, entCode, knowledgeBaseId)) {
                versionKeys.add(toVersionKey(document));
            }
        }
        Map<DocumentVersionKey, String> readySources =
                this.knowledgeDocumentService.findReadyVersionSources(versionKeys);

        List<Document> eligible = new ArrayList<>();
        Set<String> seenChunks = new HashSet<>();
        for (Document document : candidates) {
            if (!isCandidateUsable(document, entCode, knowledgeBaseId)) {
                continue;
            }
            DocumentVersionKey key = toVersionKey(document);
            if (!readySources.containsKey(key)) {
                continue;
            }
            String chunkId = String.valueOf(document.getMetadata().getOrDefault("chunk_id", ""));
            if (!seenChunks.add(chunkId)) {
                continue;
            }
            eligible.add(document);
        }

        return diversifyByDocument(eligible, topK);
    }

    /**
     * 按文档分散：先每个文档取最相关的一条，再按相关度补齐。
     * <p>
     * 一个几百页的手册会切成上千个分片，同一文档的相邻分片在余弦相似度下极易霸榜 topK，
     * 导致回答只依据单一来源、视野狭窄。
     */
    private List<Document> diversifyByDocument(List<Document> documents, int topK) {
        Map<String, List<Document>> grouped = new LinkedHashMap<>();
        for (Document document : documents) {
            grouped.computeIfAbsent(String.valueOf(document.getMetadata().get("document_id")),
                    key -> new ArrayList<>()).add(document);
        }

        List<Document> result = new ArrayList<>();
        for (List<Document> perDocument : grouped.values()) {
            if (result.size() >= topK) {
                break;
            }
            result.add(perDocument.get(0));
        }

        if (result.size() < topK) {
            Set<String> picked = new HashSet<>();
            for (Document document : result) {
                picked.add(String.valueOf(document.getMetadata().get("chunk_id")));
            }
            for (Document document : documents) {
                if (result.size() >= topK) {
                    break;
                }
                if (picked.add(String.valueOf(document.getMetadata().get("chunk_id")))) {
                    result.add(document);
                }
            }
        }
        return result;
    }

    private boolean isCandidateUsable(Document document, String entCode, Long knowledgeBaseId) {
        return matchesScope(document, entCode, knowledgeBaseId) && hasCompleteMetadata(document);
    }

    /** 租户与知识库范围校验（向量库侧已有过滤，这里是纵深防御） */
    private boolean matchesScope(Document document, String entCode, Long knowledgeBaseId) {
        Map<String, Object> metadata = document.getMetadata();
        return entCode.equals(String.valueOf(metadata.get("ent_code")))
                && knowledgeBaseId.toString().equals(String.valueOf(metadata.get("knowledge_base_id")));
    }

    /**
     * 身份完整性校验：缺少稳定标识或模型指纹不匹配的向量一律排除。
     * <p>
     * 旧格式向量没有 {@code document_id} / {@code version}，无法产生可验证的引用，
     * 与其带来错误引用不如直接不参与检索。
     */
    private boolean hasCompleteMetadata(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return toVersionKey(document) != null
                && isNotBlank(metadata.get("chunk_id"))
                && isNotBlank(metadata.get("source"))
                && this.ragProperties.getEmbeddingModel().equals(String.valueOf(metadata.get("embedding_model")))
                && toInt(metadata.get("chunk_index")) >= 0;
    }

    private DocumentVersionKey toVersionKey(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        Object documentId = metadata.get("document_id");
        if (documentId == null || String.valueOf(documentId).isBlank()) {
            return null;
        }
        int version = toInt(metadata.get("document_version"));
        if (version <= 0) {
            return null;
        }
        return new DocumentVersionKey(String.valueOf(documentId), version);
    }

    private int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return -1;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private boolean isNotBlank(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

}
