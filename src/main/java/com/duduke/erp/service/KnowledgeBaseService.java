package com.duduke.erp.service;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duduke.erp.entity.dto.KnowledgeBaseSaveDTO;
import com.duduke.erp.entity.po.KnowledgeBase;
import com.duduke.erp.entity.po.KnowledgeDocument;
import com.duduke.erp.entity.vo.KnowledgeBaseVO;
import com.duduke.erp.mapper.KnowledgeBaseMapper;
import com.duduke.erp.mapper.KnowledgeDocumentMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 知识库业务。
 * <p>
 * 租户隔离由 MyBatis-Plus 租户插件完成；删除采用软删，保证历史文档的引用仍可追溯。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    /** 首次上传且未指定知识库时自动创建的默认库名称 */
    private static final String DEFAULT_NAME = "默认知识库";

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    /**
     * 列出当前租户的全部可用知识库，默认库排在最前。
     */
    public List<KnowledgeBaseVO> list() {
        List<KnowledgeBase> rows = this.knowledgeBaseMapper.selectList(
                Wrappers.<KnowledgeBase>lambdaQuery()
                        .ne(KnowledgeBase::getStatus, "deleted")
                        .orderByDesc(KnowledgeBase::getIsDefault)
                        .orderByDesc(KnowledgeBase::getId));
        return rows.stream().map(this::toVO).toList();
    }

    public KnowledgeBaseVO get(Long id) {
        return toVO(requireExists(id));
    }

    @Transactional
    public Long create(KnowledgeBaseSaveDTO dto) {
        String name = requireName(dto);
        KnowledgeBase po = new KnowledgeBase();
        fill(po, dto, name);
        LocalDateTime now = LocalDateTime.now();
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        this.knowledgeBaseMapper.insert(po);
        return po.getId();
    }

    @Transactional
    public void update(Long id, KnowledgeBaseSaveDTO dto) {
        requireExists(id);
        String name = requireName(dto);
        KnowledgeBase po = new KnowledgeBase();
        po.setId(id);
        fill(po, dto, name);
        po.setUpdatedAt(LocalDateTime.now());
        this.knowledgeBaseMapper.updateById(po);
    }

    /**
     * 软删。文档仍保留，便于审计与后续清理任务处理。
     */
    @Transactional
    public void remove(Long id) {
        requireExists(id);
        KnowledgeBase po = new KnowledgeBase();
        po.setId(id);
        po.setStatus("deleted");
        po.setUpdatedAt(LocalDateTime.now());
        this.knowledgeBaseMapper.updateById(po);
    }

    /**
     * 解析目标知识库：指定则校验可用，未指定则取默认库，没有默认库时懒创建。
     * <p>
     * 上传接口不强制前端先建库，首次使用自动落到默认库。
     */
    @Transactional
    public KnowledgeBase resolveActive(Long knowledgeBaseId) {
        if (knowledgeBaseId != null) {
            KnowledgeBase kb = requireExists(knowledgeBaseId);
            if (!"active".equals(kb.getStatus())) {
                throw new IllegalArgumentException("知识库不可用：" + kb.getName());
            }
            return kb;
        }

        List<KnowledgeBase> defaults = this.knowledgeBaseMapper.selectList(
                Wrappers.<KnowledgeBase>lambdaQuery()
                        .eq(KnowledgeBase::getIsDefault, true)
                        .eq(KnowledgeBase::getStatus, "active")
                        .orderByAsc(KnowledgeBase::getId));
        if (!defaults.isEmpty()) {
            return defaults.get(0);
        }

        KnowledgeBase created = new KnowledgeBase();
        created.setName(DEFAULT_NAME);
        created.setIsDefault(true);
        created.setStatus("active");
        LocalDateTime now = LocalDateTime.now();
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        this.knowledgeBaseMapper.insert(created);
        return created;
    }

    private String requireName(KnowledgeBaseSaveDTO dto) {
        String name = dto.name() == null ? "" : dto.name().trim();
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("知识库名称不能为空");
        }
        return name;
    }

    private void fill(KnowledgeBase po, KnowledgeBaseSaveDTO dto, String name) {
        po.setName(name);
        po.setDescription(dto.description());
        po.setIsDefault(Boolean.TRUE.equals(dto.isDefault()));
        po.setStatus("active");
    }

    private KnowledgeBase requireExists(Long id) {
        KnowledgeBase kb = this.knowledgeBaseMapper.selectById(id);
        if (kb == null || "deleted".equals(kb.getStatus())) {
            throw new IllegalArgumentException("知识库不存在或不属于当前租户");
        }
        return kb;
    }

    private KnowledgeBaseVO toVO(KnowledgeBase po) {
        Long readyCount = this.knowledgeDocumentMapper.selectCount(
                Wrappers.<KnowledgeDocument>lambdaQuery()
                        .eq(KnowledgeDocument::getKnowledgeBaseId, po.getId())
                        .eq(KnowledgeDocument::getStatus, "ready"));
        return new KnowledgeBaseVO(
                po.getId(),
                po.getName(),
                po.getDescription(),
                po.getIsDefault(),
                po.getStatus(),
                readyCount,
                po.getCreatedAt(),
                po.getUpdatedAt());
    }

}
