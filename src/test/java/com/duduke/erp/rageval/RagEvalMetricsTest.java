package com.duduke.erp.rageval;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 指标计算单测。纯函数，不需要 Spring 上下文，也不需要模型 Key。
 */
class RagEvalMetricsTest {

    private final RagEvalMetrics metrics = new RagEvalMetrics();

    @Test
    @DisplayName("Recall@5 按文档去重：同一份文档命中多个分片只算一次")
    void recallDeduplicatesByDocument() {
        RagEvalCaseResult result = result("c1", true,
                List.of("a.txt", "a.txt", "a.txt", "b.txt"),
                List.of("a.txt", "b.txt"));

        assertThat(this.metrics.recallAt5(result)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Recall@5 部分命中：命中 1/2 得 0.5")
    void recallCountsPartialHits() {
        RagEvalCaseResult result = result("c1", true, List.of("a.txt", "x.txt"), List.of("a.txt", "b.txt"));

        assertThat(this.metrics.recallAt5(result)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("Recall@5 只看前 5 条：第 6 条命中不计分")
    void recallOnlyLooksAtTopFive() {
        RagEvalCaseResult result = result("c1", true,
                List.of("x1.txt", "x2.txt", "x3.txt", "x4.txt", "x5.txt", "a.txt"),
                List.of("a.txt"));

        assertThat(this.metrics.recallAt5(result)).isZero();
    }

    @Test
    @DisplayName("MRR@5：首位命中得 1.0，第三位命中得 1/3")
    void mrrRewardsEarlierHits() {
        assertThat(this.metrics.mrrAt5(result("c1", true, List.of("a.txt"), List.of("a.txt"))))
                .isEqualTo(1.0);
        assertThat(this.metrics.mrrAt5(result("c2", true,
                List.of("x.txt", "y.txt", "a.txt"), List.of("a.txt"))))
                .isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    @DisplayName("聚合时不把不可答用例算进召回指标")
    void summaryExcludesUnanswerableFromRecall() {
        RagEvalCaseResult hit = result("c1", true, List.of("a.txt"), List.of("a.txt"), 5L);
        RagEvalCaseResult miss = result("c2", true, List.of("x.txt"), List.of("b.txt"), 15L);
        RagEvalCaseResult noAnswer = new RagEvalCaseResult("c3", false, List.of("x.txt"), List.of(),
                0, "资料中没有找到相关信息", 0, 0, List.of(), true, 25L, null);

        RagEvalSummary summary = this.metrics.summarize(List.of(hit, miss, noAnswer));

        assertThat(summary.caseCount()).isEqualTo(3);
        assertThat(summary.recallAt5()).isEqualTo(0.5);
        assertThat(summary.refusalRate()).isEqualTo(1.0);
        assertThat(summary.latencyP50()).isEqualTo(15L);
    }

    @Test
    @DisplayName("没有不可答用例时拒答率返回 1.0，而不是 0.0")
    void refusalRateIsFullWhenNoUnanswerableCase() {
        RagEvalSummary summary = this.metrics.summarize(
                List.of(result("c1", true, List.of("a.txt"), List.of("a.txt"))));

        assertThat(summary.refusalRate())
                .as("分母为 0 时返回 0.0 会被读成「一条都没拒答」，那是把无意义当成缺陷")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("空召回率按全部用例统计")
    void emptyRetrievalRateCountsAllCases() {
        RagEvalCaseResult empty = result("c1", true, List.of(), List.of("a.txt"));
        RagEvalCaseResult notEmpty = result("c2", true, List.of("a.txt"), List.of("a.txt"));

        RagEvalSummary summary = this.metrics.summarize(List.of(empty, notEmpty));

        assertThat(summary.emptyRetrievalRate()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("空输入直接报错，避免静默产出全 0 的报告")
    void rejectsEmptyResults() {
        assertThatThrownBy(() -> this.metrics.summarize(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    private RagEvalCaseResult result(String caseId, boolean answerable,
                                     List<String> retrieved, List<String> expected) {
        return result(caseId, answerable, retrieved, expected, 10L);
    }

    private RagEvalCaseResult result(String caseId, boolean answerable,
                                     List<String> retrieved, List<String> expected, long latencyMs) {
        return new RagEvalCaseResult(caseId, answerable, retrieved, expected, 0, "",
                0, 0, List.of(), false, latencyMs, null);
    }

}
