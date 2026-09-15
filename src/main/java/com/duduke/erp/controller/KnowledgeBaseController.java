package com.duduke.erp.controller;

import java.io.IOException;
import java.util.List;

import com.duduke.erp.common.exception.BusinessException;
import com.duduke.erp.entity.dto.KnowledgeBaseSaveDTO;
import com.duduke.erp.entity.vo.KnowledgeBaseVO;
import com.duduke.erp.entity.vo.KnowledgeDocumentVO;
import com.duduke.erp.entity.vo.RagSearchResult;
import com.duduke.erp.service.KnowledgeBaseService;
import com.duduke.erp.service.KnowledgeDocumentIngestionService;
import com.duduke.erp.service.KnowledgeDocumentService;
import com.duduke.erp.service.RagAnswerService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库接口。
 * <p>
 * 检索接口挂在具体知识库下（{@code /{id}/search}），避免与文档操作路径产生歧义。
 */
@RestController
@RequestMapping("/api/biz/knowledge_bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    private final KnowledgeDocumentService knowledgeDocumentService;

    private final KnowledgeDocumentIngestionService ingestionService;

    private final RagAnswerService ragAnswerService;

    @SaCheckPermission("knowledge:manage")
    @GetMapping
    public List<KnowledgeBaseVO> list() {
        return this.knowledgeBaseService.list();
    }

    @SaCheckPermission("knowledge:manage")
    @GetMapping("/{id}")
    public KnowledgeBaseVO get(@PathVariable Long id) {
        return this.knowledgeBaseService.get(id);
    }

    @SaCheckPermission("knowledge:manage")
    @PostMapping
    public Long create(@RequestBody KnowledgeBaseSaveDTO dto) {
        return this.knowledgeBaseService.create(dto);
    }

    @SaCheckPermission("knowledge:manage")
    @PutMapping("/{id}")
    public void update(@PathVariable Long id, @RequestBody KnowledgeBaseSaveDTO dto) {
        this.knowledgeBaseService.update(id, dto);
    }

    @SaCheckPermission("knowledge:manage")
    @DeleteMapping("/{id}")
    public void remove(@PathVariable Long id) {
        this.knowledgeBaseService.remove(id);
    }

    @SaCheckPermission("knowledge:manage")
    @GetMapping("/{id}/documents")
    public List<KnowledgeDocumentVO> listDocuments(@PathVariable Long id) {
        return this.knowledgeDocumentService.listDocuments(id);
    }

    /**
     * 查询单个文档的最新版本。
     * <p>
     * 异步解析期间前端轮询它拿 {@code status} 与 {@code stage}：
     * 上传接口只代表"已受理"，真正的进度要看这里。
     */
    @SaCheckPermission("knowledge:manage")
    @GetMapping("/{id}/documents/{documentId}")
    public KnowledgeDocumentVO getDocument(@PathVariable Long id,
                                           @PathVariable String documentId) {
        return this.knowledgeDocumentService.getDocument(id, documentId);
    }

    /**
     * 上传文档。不带 documentId 时为新增或按来源名追加版本；
     * 带上 documentId 表示替换该文档内容，生成新版本。
     * <p>
     * 异步开启时（{@code app.mq.async-enabled}）接口返回只代表「已受理」：
     * 版本登记为 processing，解析与向量化由消息队列异步完成。
     * 分流决策在 {@link KnowledgeDocumentIngestionService} 内部，本处不做判断。
     */
    @SaCheckPermission("knowledge:manage")
    @PostMapping("/{id}/documents")
    public void uploadDocument(@PathVariable Long id,
                               @RequestParam("file") MultipartFile file,
                               @RequestParam(value = "documentId", required = false) String documentId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        String fileName = file.getOriginalFilename();
        try {
            // 读成字节数组：异步链路要把原件写进对象存储，
            // 同步降级链路也直接消费同一份字节，两条路都需要它
            this.ingestionService.importDocument(id, fileName, file.getContentType(),
                    file.getBytes(), documentId);
        }
        catch (IOException e) {
            throw new BusinessException(500, "读取上传文件失败", e);
        }
    }

    @SaCheckPermission("knowledge:manage")
    @DeleteMapping("/{id}/documents/{documentId}")
    public void deleteDocument(@PathVariable Long id, @PathVariable String documentId) {
        this.ingestionService.deleteDocument(id, documentId);
    }

    /**
     * 纯向量检索：不调用大模型、不计费，用于验证入库效果与排查召回质量。
     */
    @SaCheckPermission("knowledge:manage")
    @GetMapping("/{id}/search")
    public List<RagSearchResult> search(@PathVariable Long id,
                                        @RequestParam("query") String query,
                                        @RequestParam(value = "topK", required = false) Integer topK) {
        return this.ragAnswerService.search(id, query, topK);
    }

}
