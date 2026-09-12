package com.duduke.erp.entity.po;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 知识库。
 * <p>
 * 每个租户可建多个知识库；{@code isDefault} 标记默认库，上传时不指定知识库则落到这里。
 */
@Data
@TableName("knowledge_base")
public class KnowledgeBase {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String entCode;

    private String name;

    private String description;

    /** 默认知识库，上传未指定时使用 */
    private Boolean isDefault;

    /** active / inactive */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
