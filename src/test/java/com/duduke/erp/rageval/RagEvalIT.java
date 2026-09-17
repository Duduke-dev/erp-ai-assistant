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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 端到端评测：建临时知识库 → 导入 fixture → 逐条检索与问答 → 出报告 → 清理。
 *
 * <h3>为什么需要可用的 Key，以及为什么按「配置值」而不是「环境变量」判断</h3>
 * 导入 fixture 要把文档向量化，这一步走云端 embedding 模型，没 Key 跑不起来。
 * <p>
 * 但守卫条件读的是 <b>{@code spring.ai.openai.api-key} 的最终值</b>，而不是
 * {@code DASHSCOPE_API_KEY} 环境变量——因为 Key 现在放在 {@code application-local.yml}
 * （由 {@code spring.profiles.default=local} 自动加载），环境变量里并没有它。
 * 用环境变量做守卫会出现最误导的一种情况：<b>Key 明明配好了，评测却仍被跳过</b>。
 *
 * <h3>为什么要关掉 MQ 监听器（{@code auto-startup=false}）</h3>
 * 本评测走同步导入，不需要消息队列。但 Spring 的测试上下文会缓存到 JVM 结束，
 * 若这里的监听器容器处于启动状态，它会<b>在整个测试进程生命周期内持续消费主队列</b>——
 * 于是其它关掉自启动、靠 {@code receiveAndConvert} 直接取消息的 MQ 测试全部拿到 null。
 * 这个隔离缺陷在评测被跳过时看不出来，一旦真跑就会让 4 个无关测试变红。
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
 *
 * <h3>⚠️ 已知局限：v1 数据集的 Recall@5 没有区分度</h3>
 * 目标库只有 6 份 fixture 文档，而 {@code TOP_K = 5} —— 检索几乎总会把整个文档集捞回来，
 * 于是"期望文档被召回"是必然的：<b>首次运行 Recall@5 = 1.0 属于指标虚高，不能读成"检索质量满分"</b>。
 * 在当前规模下真正有信息量的是 <b>MRR@5</b>（期望文档的排名位置）与越界计数：
 * 前者若从 1.0 掉下来，说明排序真的变差了。
 * <p>
 * 要让 Recall 恢复区分度，二选一：
 * <ol>
 *   <li>把 fixture 文档扩到 15 份以上（推荐，同时也更像真实知识库）；</li>
 *   <li>或把评测用的 {@code TOP_K} 降到 2，让"召回"变成一件需要努力的事。</li>
 * </ol>
 * 二者都动会影响与历史基线的可比性，届时应新建 {@code v2} 数据集并重建基线。
 */
@Slf4j
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class RagEvalIT {

    /**
     * 数据集版本。
     * <p>
     * v1（6 份文档 / 15 条用例）保留不动——它的指标已固化在 `v1/baseline.json`，
     * 用于对照"扩数据集前后"的变化。
     * v2 把目标文档扩到 15 份、用例扩到 43 条，让 Recall@5 恢复区分度
     * （v1 里 TOP_K=5 对 6 份文档，必中，指标没有意义）。
     */
    private static final String VERSION = "v2";

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

    /** 读最终生效的 api-key（含 profile 文件与命令行覆盖），而不是只看环境变量 */
    @Autowired
    private Environment environment;

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
        // 凭据不可用时"跳过"而不是"失败"：评测是可选的质量门禁，
        // 在没配 Key 的机器（CI、新克隆的仓库）上应该安静跳过，而不是把构建判红
        String apiKey = this.environment.getProperty("spring.ai.openai.api-key", "");
        Assumptions.assumeTrue(
                apiKey.startsWith("sk-") && !apiKey.contains("placeholder"),
                "未配置有效的 DashScope Key（spring.ai.openai.api-key），跳过 RAG 端到端评测");

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
