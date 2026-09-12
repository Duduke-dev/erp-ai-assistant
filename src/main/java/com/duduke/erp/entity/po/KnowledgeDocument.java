package com.duduke.erp.entity.po;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 知识文档。
 * <p>
 * 同一 {@code documentId} 的多次导入以 {@code version} 递增形成版本链。
 * 检索只认 {@code status = ready} 且 {@code embeddingModel} 为当前模型指纹的版本——
 * 后者是必需的：不同嵌入模型的语义空间不通用，即使维度相同也不能混用。
 */
@Data
@TableName("knowledge_document")
public class KnowledgeDocument {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String entCode;

    private Long knowledgeBaseId;

    /** 稳定文档 ID（UUID），与版本号解耦：替换内容 = 同一 documentId 下新增一行 */
    private String documentId;

    private String title;

    private Integer version;

    /** processing / ready / failed / superseded / deleted */
    private String status;

    /** 对象存储定位，M2.5 接入 RustFS 后启用 */
    private String bucket;

    private String objectKey;

    private Long fileSize;

    private String contentType;

    private Integer chunkCount;

    /** 生成向量时用的模型指纹，检索时强制比对，防止不同语义空间的向量混用 */
    private String embeddingModel;

    /** 文档内容 SHA-256，用于审计与内容变更判断 */
    private String checksumSha256;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
