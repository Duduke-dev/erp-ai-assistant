package com.duduke.erp.rageval;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duduke.erp.entity.dto.AskDTO;
import com.duduke.erp.entity.dto.KnowledgeBaseSaveDTO;
import com.duduke.erp.entity.po.ChatConversation;
import com.duduke.erp.entity.po.ChatMessage;
import com.duduke.erp.entity.vo.AskVO;
import com.duduke.erp.entity.vo.KnowledgeDocumentVO;
import com.duduke.erp.entity.vo.RagSearchResult;
import com.duduke.erp.mapper.ChatConversationMapper;
import com.duduke.erp.mapper.ChatMessageMapper;
import com.duduke.erp.service.AssistantService;
import com.duduke.erp.service.KnowledgeBaseService;
import com.duduke.erp.service.KnowledgeDocumentIngestionService;
import com.duduke.erp.service.KnowledgeDocumentService;
import com.duduke.erp.service.RagAnswerService;
import com.duduke.erp.tenant.TenantContext;

import lombok.extern.slf4j.Slf4j;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 端到端评测：建临时知识库 → 导入 fixture → 逐条检索与问答 → 出报告 → 清理。
 *
 * <h3>为什么必须设 DASHSCOPE_API_KEY 才跑</h3>
 * 导入 fixture 要把文档向量化，这一步走云端 embedding 模型。
 * 没有可用的 Key 时整个评测没有任何意义——不设 Key 就明确跳过，
 * 而不是让它带着占位符跑到一半再报错。
 *
 * <h3>两层指标各自的作用</h3>
 * <ul>
 *   <li><b>检索层</b>（Recall@5 / MRR@5 / 空召回）：回答「资料找没找到」，
 *       不受模型输出波动影响，是调阈值与分块参数时的主指标；</li>
 *   <li><b>答案层</b>（关键事实、禁止结论、拒答）：回答「答得对不对、有没有胡编」，
 *       依赖对话模型，因此只做硬门禁（错误结论零容忍），不参与基线回归。</li>
 * </ul>
 *
 * <h3>为什么要建干扰库</h3>
 * 干扰文档与目标文档主题相近（同一规范的未发布草稿），一旦范围过滤失效，
 * 它极可能被召回并让模型答出错误数字。这条用例是「跨库串档」的探针。
 */
@Slf4j
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = "sk-(?!placeholder).+")
class RagEvalIT {

    private static final String VERSION = "v1";

    private static final String ENT_CODE = "DEMO";

    private static final int TOP_K = 5;

    /** 文件名以此开头视为干扰文档，只进干扰库 */
    private static final String INTERFERENCE_PREFIX = "interference-";

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @Autowired
    private KnowledgeDocumentIngestionService ingestionService;

    @Autowired
    private RagAnswerService ragAnswerService;

    @Autowired
    private AssistantService assistantService;

    @Autowired
    private ChatConversationMapper chatConversationMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    private final RagEvalDatasetLoader loader = new RagEvalDatasetLoader();

    private final RagEvalMetrics metrics = new RagEvalMetrics();

    private final RagEvalAnswerChecker answerChecker = new RagEvalAnswerChecker();

    private final RagEvalGate gate = new RagEvalGate();

    private final RagEvalReportWriter reportWriter = new RagEvalReportWriter();

    private Long targetKnowledgeBaseId;

    private Long interferenceKnowledgeBaseId;

    private Set<String> targetSources = Set.of();

    private final List<String> createdConversations = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TenantContext.set(ENT_CODE, 1L);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        this.targetKnowledgeBaseId = this.knowledgeBaseService.create(new KnowledgeBaseSaveDTO(
                "rag-eval-target-" + suffix, "RAG 评测目标库（自动创建，可删）", false));
        this.interferenceKnowledgeBaseId = this.knowledgeBaseService.create(new KnowledgeBaseSaveDTO(
                "rag-eval-interference-" + suffix, "RAG 评测干扰库（自动创建，可删）", false));

        Map<String, byte[]> documents = this.loader.loadDocuments(VERSION);
        List<String> targets = new ArrayList<>();
        documents.forEach((fileName, content) -> {
            boolean interference = fileName.startsWith(INTERFERENCE_PREFIX);
            Long knowledgeBaseId = interference
                    ? this.interferenceKnowledgeBaseId : this.targetKnowledgeBaseId;
            this.ingestionService.importFile(knowledgeBaseId, fileName, "text/plain",
                    content.length, new ByteArrayInputStream(content), null);
            if (!interference) {
                targets.add(fileName);
            }
        });
        this.targetSources = Set.copyOf(targets);
        assertThat(this.targetSources).as("目标库 fixture 不能为空").isNotEmpty();
    }

    @AfterEach
    void tearDown() {
        try {
            cleanupKnowledgeBase(this.targetKnowledgeBaseId);
            cleanupKnowledgeBase(this.interferenceKnowledgeBaseId);
            for (String conversationId : this.createdConversations) {
                this.chatMessageMapper.delete(Wrappers.<ChatMessage>lambdaQuery()
                        .eq(ChatMessage::getConversationId, conversationId));
                this.chatConversationMapper.delete(Wrappers.<ChatConversation>lambdaQuery()
                        .eq(ChatConversation::getConversationId, conversationId));
            }
        }
        finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("评测全量用例：写报告，并按门禁判定（越界、错误结论、基线回归零容忍）")
    void evaluateDataset() {
        List<RagEvalCase> cases = this.loader.loadCases(VERSION);
        List<RagEvalCaseResult> results = new ArrayList<>();
        for (RagEvalCase evalCase : cases) {
            results.add(runCase(evalCase));
        }

        RagEvalSummary summary = this.metrics.summarize(results);
        RagEvalBaseline baseline = this.loader.loadBaseline(VERSION);
        RagEvalGate.RagEvalGateResult gateResult =
                this.gate.evaluate(summary, baseline, results, cases.size());
        Path report = this.reportWriter.write(VERSION, summary, gateResult, results, baseline);

        log.info("RAG 评测完成：用例 {}，Recall@5={}，MRR@5={}，越界={}，报告={}",
                summary.caseCount(), summary.recallAt5(), summary.mrrAt5(),
                summary.boundaryViolationCount(), report);

        assertThat(gateResult.failures()).as("RAG 评测门禁未通过").isEmpty();
    }

    private RagEvalCaseResult runCase(RagEvalCase evalCase) {
        long started = System.currentTimeMillis();
        try {
            List<RagSearchResult> retrieved = this.ragAnswerService.search(
                    this.targetKnowledgeBaseId, evalCase.question(), TOP_K);
            List<String> sources = retrieved.stream()
                    .map(RagSearchResult::source)
                    .toList();
            // 空来源说明元数据缺失，单独算缺陷代码里的边界，不计入越界
            int boundaryViolations = (int) sources.stream()
                    .filter(source -> !source.isEmpty() && !this.targetSources.contains(source))
                    .count();

            AskVO answer = this.assistantService.ask(
                    new AskDTO(null, evalCase.question(), "knowledge", this.targetKnowledgeBaseId));
            if (answer.conversationId() != null) {
                this.createdConversations.add(answer.conversationId());
            }

            String text = answer.answer() == null ? "" : answer.answer();
            RagEvalAnswerChecker.RagEvalAnswerCheck check = this.answerChecker.check(
                    text, evalCase.keyFacts(), evalCase.forbiddenPhrases());
            return new RagEvalCaseResult(evalCase.caseId(), evalCase.answerable(), sources,
                    evalCase.expectedSources(), boundaryViolations, text,
                    check.matchedKeyFactCount(), check.totalKeyFactCount(),
                    check.criticalFactViolations(), this.answerChecker.isRefusal(text),
                    System.currentTimeMillis() - started, null);
        }
        catch (RuntimeException e) {
            // 单条失败不吞掉：记进结果，由门禁统一判定为失败
            log.warn("评测用例执行失败：caseId={}", evalCase.caseId(), e);
            return new RagEvalCaseResult(evalCase.caseId(), evalCase.answerable(), List.of(),
                    evalCase.expectedSources(), 0, "", 0, evalCase.keyFacts().size(),
                    List.of(), false, System.currentTimeMillis() - started, e.getMessage());
        }
    }

    /** 清理：先删文档（连带向量与原件），再软删知识库 */
    private void cleanupKnowledgeBase(Long knowledgeBaseId) {
        if (knowledgeBaseId == null) {
            return;
        }
        Map<String, KnowledgeDocumentVO> documents = new LinkedHashMap<>();
        for (KnowledgeDocumentVO document : this.knowledgeDocumentService.listDocuments(knowledgeBaseId)) {
            documents.put(document.documentId(), document);
        }
        for (KnowledgeDocumentVO document : documents.values()) {
            try {
                this.ingestionService.deleteDocument(knowledgeBaseId, document.documentId());
            }
            catch (RuntimeException e) {
                log.warn("清理评测文档失败：documentId={}", document.documentId(), e);
            }
        }
        this.knowledgeBaseService.remove(knowledgeBaseId);
    }

}
