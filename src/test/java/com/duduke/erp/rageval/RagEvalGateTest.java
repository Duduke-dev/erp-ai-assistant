package com.duduke.erp.rageval;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 门禁单测：确认「该拦的拦住、不该拦的放过」。
 */
class RagEvalGateTest {

    private final RagEvalGate gate = new RagEvalGate();

    @Test
    @DisplayName("干净的评测结果通过门禁")
    void cleanResultPasses() {
        RagEvalCaseResult result = result("c1", 0, List.of(), 0);
        RagEvalSummary summary = new RagEvalMetrics().summarize(List.of(result));

        RagEvalGate.RagEvalGateResult gateResult = this.gate.evaluate(summary, null, List.of(result), 1);

        assertThat(gateResult.passed()).isTrue();
        assertThat(gateResult.failures()).isEmpty();
    }

    @Test
    @DisplayName("出现越界召回即失败——这是隔离缺陷，不是质量问题")
    void boundaryViolationFails() {
        RagEvalCaseResult result = result("c1", 2, List.of(), 0);
        RagEvalSummary summary = new RagEvalMetrics().summarize(List.of(result));

        RagEvalGate.RagEvalGateResult gateResult = this.gate.evaluate(summary, null, List.of(result), 1);

        assertThat(gateResult.passed()).isFalse();
        assertThat(gateResult.failures()).anyMatch(failure -> failure.contains("越界召回"));
    }

    @Test
    @DisplayName("出现关键事实错误结论即失败，并带出原始短语")
    void criticalFactViolationFails() {
        RagEvalCaseResult result = result("c-inventory", 0, List.of("45 天用量"), 0);
        RagEvalSummary summary = new RagEvalMetrics().summarize(List.of(result));

        RagEvalGate.RagEvalGateResult gateResult = this.gate.evaluate(summary, null, List.of(result), 1);

        assertThat(gateResult.passed()).isFalse();
        assertThat(gateResult.failures()).anyMatch(failure ->
                failure.contains("c-inventory") && failure.contains("45 天用量"));
    }

    @Test
    @DisplayName("用例没跑全即失败：少跑几条会让指标虚高")
    void incompleteCaseSetFails() {
        RagEvalCaseResult result = result("c1", 0, List.of(), 0);
        RagEvalSummary summary = new RagEvalMetrics().summarize(List.of(result));

        RagEvalGate.RagEvalGateResult gateResult = this.gate.evaluate(summary, null, List.of(result), 5);

        assertThat(gateResult.passed()).isFalse();
        assertThat(gateResult.failures()).anyMatch(failure -> failure.contains("未完整执行"));
    }

    @Test
    @DisplayName("用例执行报错即失败，不让异常用例静默拖低均值")
    void caseErrorFails() {
        RagEvalCaseResult result = new RagEvalCaseResult("c1", true, List.of(), List.of("a.txt"),
                0, "", 0, 0, List.of(), false, 1L, "模型调用超时");
        RagEvalSummary summary = new RagEvalMetrics().summarize(List.of(result));

        RagEvalGate.RagEvalGateResult gateResult = this.gate.evaluate(summary, null, List.of(result), 1);

        assertThat(gateResult.passed()).isFalse();
        assertThat(gateResult.failures()).anyMatch(failure -> failure.contains("模型调用超时"));
    }

    @Test
    @DisplayName("Recall@5 相对基线下降超过 5 个百分点即失败")
    void recallRegressionBeyondFivePointsFails() {
        // 召回的是别的文档：Recall@5 = 0，相对基线 0.90 属于明显回归
        RagEvalCaseResult miss = new RagEvalCaseResult("c1", true, List.of("x.txt"), List.of("a.txt"),
                0, "", 0, 0, List.of(), false, 1L, null);
        RagEvalSummary summary = new RagEvalMetrics().summarize(List.of(miss));

        RagEvalGate.RagEvalGateResult gateResult = this.gate.evaluate(
                summary, new RagEvalBaseline(0.90, 0.80), List.of(miss), 1);

        assertThat(summary.recallAt5()).isZero();
        assertThat(gateResult.passed()).isFalse();
        assertThat(gateResult.failures()).anyMatch(failure -> failure.contains("Recall@5 相对基线"));
    }

    @Test
    @DisplayName("下降 5 个百分点以内放过：调参有正常波动")
    void smallRegressionPasses() {
        RagEvalCaseResult hit = result("c1", 0, List.of(), 0);
        RagEvalSummary summary = new RagEvalMetrics().summarize(List.of(hit));

        RagEvalGate.RagEvalGateResult gateResult = this.gate.evaluate(
                summary, new RagEvalBaseline(1.0, 1.0), List.of(hit), 1);

        assertThat(summary.recallAt5()).isEqualTo(1.0);
        assertThat(gateResult.passed()).isTrue();
    }

    private RagEvalCaseResult result(String caseId, int boundaryViolations,
                                     List<String> criticalViolations, int latencyMs) {
        return new RagEvalCaseResult(caseId, true, List.of("a.txt"), List.of("a.txt"),
                boundaryViolations, "", 0, 0, criticalViolations, false, latencyMs, null);
    }

}
