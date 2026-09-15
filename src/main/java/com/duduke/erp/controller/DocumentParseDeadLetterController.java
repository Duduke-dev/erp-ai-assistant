package com.duduke.erp.controller;

import java.util.List;

import com.duduke.erp.entity.vo.DocumentParseDeadLetterVO;
import com.duduke.erp.service.DocumentParseDeadLetterService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 解析死信管理。
 *
 * <h3>为什么读、写要分开授权</h3>
 * 看列表只是知道"哪些任务失败了"，属于知识库运维的日常视野（{@code knowledge:manage}）；
 * 而重投会重置文档状态并重新触发解析，等于改变知识库内容，
 * 风险面不同，因此单独收在 {@code knowledge:dlq:manage}。
 *
 * <h3>为什么没有 DELETE</h3>
 * 死信是故障证据，丢弃只改状态不删记录。真要彻底清除走数据库运维，
 * 不给接口——删除能力一旦给出，就会被当成"清掉报错"的快捷方式。
 */
@RestController
@RequestMapping("/api/biz/document_parse_dead_letters")
@RequiredArgsConstructor
public class DocumentParseDeadLetterController {

    private final DocumentParseDeadLetterService deadLetterService;

    @SaCheckPermission("knowledge:manage")
    @GetMapping
    public List<DocumentParseDeadLetterVO> list() {
        return this.deadLetterService.list();
    }

    /** 重投：重置文档版本为 processing 后，把原始消息体原样发回主队列 */
    @SaCheckPermission("knowledge:dlq:manage")
    @PostMapping("/{id}/retry")
    public void retry(@PathVariable Long id) {
        this.deadLetterService.retry(id);
    }

    /** 丢弃：不再处理，记录保留 */
    @SaCheckPermission("knowledge:dlq:manage")
    @PostMapping("/{id}/discard")
    public void discard(@PathVariable Long id) {
        this.deadLetterService.discard(id);
    }

}
