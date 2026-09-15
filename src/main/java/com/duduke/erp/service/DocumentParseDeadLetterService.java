package com.duduke.erp.service;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duduke.erp.common.exception.BusinessException;
import com.duduke.erp.entity.dto.DocumentParseMessage;
import com.duduke.erp.entity.po.DocumentParseDeadLetter;
import com.duduke.erp.entity.vo.DocumentParseDeadLetterVO;
import com.duduke.erp.mapper.DocumentParseDeadLetterMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 解析死信的登记与处置。
 *
 * <h3>为什么必须落库</h3>
 * 死信消息只存在于 RabbitMQ 里时，应用内看不见、也无法重投，
 * 用户端的表现就是"上传失败了但没人知道为什么"。落库后，
 * 失败任务变成一条可列举、可处置、可审计的记录。
 *
 * <h3>重投的关键一步：把文档状态改回 processing</h3>
 * {@code markReady} 只接受处于 processing 的版本，而死信发生时版本已是 failed。
 * 不先重置就重投，消费侧会立刻再次失败——表现为"重投了但没用"，
 * 且没有报错指向真正的原因。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentParseDeadLetterService {

    /** 待处置 */
    public static final String STATUS_PENDING = "pending";

    /** 已重投（不再重复处置） */
    public static final String STATUS_RETRIED = "retried";

    /** 已丢弃（保留记录，只是不再处理） */
    public static final String STATUS_DISCARDED = "discarded";

    private final DocumentParseDeadLetterMapper deadLetterMapper;

    private final DocumentParsePublisher publisher;

    private final KnowledgeDocumentService knowledgeDocumentService;

    /**
     * 登记一条死信。
     * <p>
     * 同一文档同一版本重复死信时只更新已有行：重投后再次失败是很常见的，
     * 每次都插一行会让列表被同一个故障刷满，反而看不出有哪些任务。
     * <p>
     * 不显式写 {@code entCode}——与项目其余插入一致，由租户插件在 INSERT 时注入。
     */
    @Transactional
    public void record(DocumentParseMessage message, String payload, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        DocumentParseDeadLetter existing = findPending(message.documentId(), message.version());
        if (existing != null) {
            existing.setErrorMessage(truncate(errorMessage));
            existing.setUpdatedAt(now);
            this.deadLetterMapper.updateById(existing);
            return;
        }

        DocumentParseDeadLetter po = new DocumentParseDeadLetter();
        po.setDocumentId(message.documentId());
        po.setKnowledgeBaseId(message.knowledgeBaseId());
        po.setVersion(message.version());
        po.setObjectKey(message.objectKey());
        po.setFileName(message.fileName());
        po.setPayload(payload);
        po.setErrorMessage(truncate(errorMessage));
        po.setStatus(STATUS_PENDING);
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        this.deadLetterMapper.insert(po);
        log.warn("解析任务进入死信：documentId={}, version={}, reason={}",
                message.documentId(), message.version(), errorMessage);
    }

    /**
     * 本租户的死信列表，未处置在前。
     */
    public List<DocumentParseDeadLetterVO> list() {
        return this.deadLetterMapper.selectList(
                        Wrappers.<DocumentParseDeadLetter>lambdaQuery()
                                .orderByAsc(DocumentParseDeadLetter::getStatus)
                                .orderByDesc(DocumentParseDeadLetter::getId))
                .stream()
                .map(this::toVO)
                .toList();
    }

    /**
     * 重投：把原始消息体原样发回主队列，并把文档版本重置为 processing。
     */
    @Transactional
    public void retry(Long id) {
        DocumentParseDeadLetter po = require(id);
        if (!STATUS_PENDING.equals(po.getStatus())) {
            throw new BusinessException("只有待处置的死信可以重投，当前状态：" + po.getStatus());
        }
        this.knowledgeDocumentService.resetToProcessing(po.getDocumentId(), po.getVersion());
        this.publisher.republish(po.getPayload());

        po.setStatus(STATUS_RETRIED);
        po.setUpdatedAt(LocalDateTime.now());
        this.deadLetterMapper.updateById(po);
    }

    /**
     * 丢弃：不再处理，但保留记录——死信本身就是故障证据，删掉等于丢线索。
     */
    @Transactional
    public void discard(Long id) {
        DocumentParseDeadLetter po = require(id);
        if (!STATUS_PENDING.equals(po.getStatus())) {
            throw new BusinessException("只有待处置的死信可以丢弃，当前状态：" + po.getStatus());
        }
        po.setStatus(STATUS_DISCARDED);
        po.setUpdatedAt(LocalDateTime.now());
        this.deadLetterMapper.updateById(po);
    }

    /**
     * 删除死信记录与它对应的对象存储原件，仅供测试清理与人工彻底清除使用。
     */
    public void deleteForCleanup(Long id) {
        this.deadLetterMapper.deleteById(id);
    }

    private DocumentParseDeadLetter findPending(String documentId, Integer version) {
        return this.deadLetterMapper.selectOne(
                Wrappers.<DocumentParseDeadLetter>lambdaQuery()
                        .eq(DocumentParseDeadLetter::getDocumentId, documentId)
                        .eq(DocumentParseDeadLetter::getVersion, version)
                        .eq(DocumentParseDeadLetter::getStatus, STATUS_PENDING));
    }

    private DocumentParseDeadLetter require(Long id) {
        DocumentParseDeadLetter po = this.deadLetterMapper.selectById(id);
        if (po == null) {
            throw new BusinessException("死信不存在或不属于当前租户");
        }
        return po;
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }

    private DocumentParseDeadLetterVO toVO(DocumentParseDeadLetter po) {
        return new DocumentParseDeadLetterVO(
                po.getId(),
                po.getDocumentId(),
                po.getKnowledgeBaseId(),
                po.getVersion(),
                po.getFileName(),
                po.getObjectKey(),
                po.getErrorMessage(),
                po.getStatus(),
                po.getCreatedAt(),
                po.getUpdatedAt());
    }

}
