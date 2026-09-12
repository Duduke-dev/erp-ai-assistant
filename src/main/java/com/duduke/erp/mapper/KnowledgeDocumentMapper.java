package com.duduke.erp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.duduke.erp.entity.po.KnowledgeDocument;

/**
 * 知识文档数据访问。
 * <p>
 * 版本相关查询（含 {@code FOR UPDATE} 行锁）由 Service 用 LambdaQueryWrapper 拼装，
 * 以便让租户插件自动追加 {@code ent_code} 条件，避免手写租户字段。
 */
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {
}
