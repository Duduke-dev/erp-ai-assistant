package com.duduke.erp.service;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.duduke.erp.config.RagProperties;
import com.duduke.erp.entity.dto.ManagedDocumentLoadResult;
import com.duduke.erp.entity.dto.ManagedDocumentMetadata;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;

/**
 * 文档解析、分块与向量化。
 * <p>
 * 刻意采用「先写向量、再改数据库状态」的顺序：向量库与关系库不共享事务，
 * 这个顺序能保证状态标为 ready 时向量一定已完整写入；中途失败则反向清理残留向量。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentLoaderService {

    private final VectorStore vectorStore;

    private final RagProperties ragProperties;

    /**
     * 解析文档流并写入向量库。
     *
     * @return 分片数与原文校验和
     */
    public ManagedDocumentLoadResult loadAndStore(InputStream inputStream,
                                                  ManagedDocumentMetadata metadata) {
        MessageDigest digest = newSha256();
        try (DigestInputStream digestStream = new DigestInputStream(inputStream, digest)) {
            List<Document> chunks = readAndSplit(digestStream, metadata.sourceName());
            if (chunks.isEmpty()) {
                throw new IllegalArgumentException("文档解析后没有可用内容：" + metadata.sourceName());
            }
            addWithMetadata(chunks, metadata);
            return new ManagedDocumentLoadResult(chunks.size(), HexFormat.of().formatHex(digest.digest()));
        } catch (IOException e) {
            throw new IllegalStateException("文档解析失败：" + metadata.sourceName(), e);
        }
    }

    /**
     * 删除某版本的全部向量。
     * <p>
     * 按 {@code ent_code + knowledge_base_id + document_id + document_version} 四元组精确匹配——
     * 只按文件名删会误伤其他租户或其他知识库下的同名文档。
     */
    public void deleteVersion(ManagedDocumentMetadata metadata) {
        this.vectorStore.delete(buildVersionFilter(metadata));
    }

    private List<Document> readAndSplit(InputStream source, String sourceName) {
        InputStreamResource resource = new InputStreamResource(source) {
            @Override
            public String getFilename() {
                return sourceName;
            }
        };
        List<Document> parsed = new TikaDocumentReader(resource).get();
        return tokenSplitter().apply(parsed);
    }

    private TokenTextSplitter tokenSplitter() {
        return TokenTextSplitter.builder()
                .withChunkSize(this.ragProperties.getChunkSize())
                .withMinChunkSizeChars(this.ragProperties.getMinChunkSizeChars())
                .withMinChunkLengthToEmbed(this.ragProperties.getMinChunkLengthToEmbed())
                .withMaxNumChunks(this.ragProperties.getMaxNumChunks())
                .build();
    }

    private void addWithMetadata(List<Document> chunks, ManagedDocumentMetadata metadata) {
        List<Document> managed = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            Document chunk = chunks.get(index);
            Map<String, Object> fields = new HashMap<>(chunk.getMetadata());
            fields.putAll(metadataFields(metadata, index));
            managed.add(Document.builder()
                    .id(UUID.randomUUID().toString())
                    .text(chunk.getText())
                    .metadata(fields)
                    .build());
        }

        int batchSize = this.ragProperties.getVectorWriteBatchSize();
        try {
            for (int from = 0; from < managed.size(); from += batchSize) {
                int to = Math.min(from + batchSize, managed.size());
                this.vectorStore.add(managed.subList(from, to));
            }
        } catch (RuntimeException e) {
            // 写向量失败时尽力清掉本版本已写入的部分，避免残留脏向量干扰后续检索
            try {
                deleteVersion(metadata);
            } catch (RuntimeException cleanupFailure) {
                log.warn("清理残留向量失败，documentId={}, version={}",
                        metadata.documentId(), metadata.version(), cleanupFailure);
            }
            throw e;
        }
    }

    private Map<String, Object> metadataFields(ManagedDocumentMetadata metadata, int chunkIndex) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("ent_code", metadata.entCode());
        fields.put("knowledge_base_id", metadata.knowledgeBaseId());
        fields.put("document_id", metadata.documentId());
        fields.put("document_version", metadata.version());
        fields.put("chunk_id", UUID.randomUUID().toString());
        fields.put("chunk_index", chunkIndex);
        fields.put("source", metadata.sourceName());
        fields.put("embedding_model", this.ragProperties.getEmbeddingModel());
        return fields;
    }

    private Filter.Expression buildVersionFilter(ManagedDocumentMetadata metadata) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        return builder.and(
                builder.eq("ent_code", metadata.entCode()),
                builder.and(
                        builder.eq("knowledge_base_id", metadata.knowledgeBaseId()),
                        builder.and(
                                builder.eq("document_id", metadata.documentId()),
                                builder.eq("document_version", metadata.version())))).build();
    }

    private MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

}
