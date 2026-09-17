package com.duduke.erp.rageval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 评测门禁：把「不能接受的情况」变成构建失败。
 *
 * <h3>硬门禁只管两件事</h3>
 * 一是<b>安全与正确性</b>（越界召回、关键错误结论、用例没跑完），二是<b>相对基线的召回回归</b>。
 * 不设 Recall@5 的绝对下限：阈值一改就可能长期飘红，而一个常年红的门禁
 * 等于没有门禁——人会条件反射地忽略它。质量趋势交给基线回归来管。
 */
public class RagEvalGate {

    /** 允许的 Recall@5 回归幅度（5 个百分点） */
    private static final double MAX_RECALL_REGRESSION = 0.05;

    /**
     * 判定门禁。
     *
     * @param summary           聚合指标
     * @param baseline          版本基线；为 null 表示首次运行，不做回归比较
     * @param results           逐用例结果
     * @param expectedCaseCount 评测集应有的用例数
     */
    public RagEvalGateResult evaluate(RagEvalSummary summary, RagEvalBaseline baseline,
                                      List<RagEvalCaseResult> results, int expectedCaseCount) {
        List<String> failures = new ArrayList<>();

        if (results.size() != expectedCaseCount) {
            failures.add("评测未完整执行：已完成 " + results.size() + "/" + expectedCaseCount);
        }
        for (RagEvalCaseResult result : results) {
            if (result.error() != null && !result.error().isBlank()) {
                failures.add("用例执行失败：caseId=" + result.caseId() + "，原因=" + result.error());
            }
        }
        if (summary.boundaryViolationCount() > 0) {
            failures.add("出现越界召回（召回了目标范围之外的文档）：" + summary.boundaryViolationCount());
        }
        if (summary.criticalFactViolationCount() > 0) {
            failures.add("出现关键事实错误结论：" + summary.criticalFactViolationCount());
        }
        for (RagEvalCaseResult result : results) {
            for (String violation : result.criticalFactViolations()) {
                failures.add("关键事实错误结论：caseId=" + result.caseId() + "，命中禁止短语=" + violation);
            }
        }
        if (baseline != null) {
            double regression = baseline.recallAt5() - summary.recallAt5();
            if (regression > MAX_RECALL_REGRESSION + 0.0000001) {
                failures.add(String.format(Locale.ROOT,
                        "Recall@5 相对基线下降超过 5 个百分点：baseline=%.4f, current=%.4f, 下降=%.4f",
                        baseline.recallAt5(), summary.recallAt5(), regression));
            }
        }
        return new RagEvalGateResult(failures.isEmpty(), List.copyOf(failures));
    }

    /**
     * 门禁结论。
     *
     * @param passed   是否通过
     * @param failures 失败明细（逐条可读，便于直接贴进报告）
     */
    public record RagEvalGateResult(boolean passed, List<String> failures) {
    }

}
