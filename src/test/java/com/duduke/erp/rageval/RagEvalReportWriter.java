package com.duduke.erp.rageval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 把评测结果写成 Markdown 报告。
 *
 * <h3>为什么报告要落盘而不是只打日志</h3>
 * 指标只看单个数字没有意义——「Recall@5 = 0.83」是好是坏，
 * 取决于比上次好了还是差了、坏在哪几条用例。逐用例明细才是调参时的实际依据，
 * 而它不可能塞进控制台。
 */
public class RagEvalReportWriter {

    private static final Path DEFAULT_OUTPUT = Path.of("target", "rag-eval-report.md");

    /**
     * 写出报告。
     *
     * @return 报告文件路径
     */
    public Path write(String version, RagEvalSummary summary,
                      RagEvalGate.RagEvalGateResult gate,
                      List<RagEvalCaseResult> results,
                      RagEvalBaseline baseline) {
        StringBuilder report = new StringBuilder();
        report.append("# RAG 评测报告（数据集 ").append(version).append("）\n\n");
        report.append("门禁：").append(gate.passed() ? "通过" : "**未通过**").append("\n\n");

        if (!gate.failures().isEmpty()) {
            report.append("## 门禁失败明细\n\n");
            for (String failure : gate.failures()) {
                report.append("- ").append(failure).append('\n');
            }
            report.append('\n');
        }

        report.append("## 聚合指标\n\n");
        report.append("| 指标 | 本次 | 基线 |\n|---|---|---|\n");
        report.append(row("用例数", String.valueOf(summary.caseCount()), "—"));
        report.append(row("Recall@5", metric(summary.recallAt5()), baselineValue(baseline, true)));
        report.append(row("MRR@5", metric(summary.mrrAt5()), baselineValue(baseline, false)));
        report.append(row("空召回率", metric(summary.emptyRetrievalRate()), "—"));
        report.append(row("关键事实命中率", metric(summary.keyFactHitRate()), "—"));
        report.append(row("拒答率（仅不可答用例）", metric(summary.refusalRate()), "—"));
        report.append(row("越界召回条数", String.valueOf(summary.boundaryViolationCount()), "—"));
        report.append(row("关键事实错误结论", String.valueOf(summary.criticalFactViolationCount()), "—"));
        report.append(row("延迟 P50 / P95", summary.latencyP50() + " / " + summary.latencyP95() + " ms", "—"));
        report.append('\n');

        report.append("## 逐用例明细\n\n");
        report.append("| 用例 | 可答 | Recall@5 | 期望来源 | 实际召回 | 关键事实 | 违规 |\n");
        report.append("|---|---|---|---|---|---|---|\n");
        RagEvalMetrics metrics = new RagEvalMetrics();
        for (RagEvalCaseResult result : results) {
            report.append("| ").append(result.caseId())
                    .append(" | ").append(result.answerable() ? "是" : "否")
                    .append(" | ").append(result.answerable()
                            ? metric(metrics.recallAt5(result)) : "—")
                    .append(" | ").append(String.join("、", result.expectedSources()))
                    .append(" | ").append(result.retrievedSources().isEmpty()
                            ? "（空）" : String.join("、", result.retrievedSources()))
                    .append(" | ").append(result.matchedKeyFactCount())
                    .append("/").append(result.totalKeyFactCount())
                    .append(" | ").append(result.criticalFactViolations().isEmpty()
                            ? "—" : String.join("、", result.criticalFactViolations()))
                    .append(" |\n");
        }

        try {
            Path parent = DEFAULT_OUTPUT.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(DEFAULT_OUTPUT, report.toString(), StandardCharsets.UTF_8);
            return DEFAULT_OUTPUT.toAbsolutePath();
        }
        catch (IOException e) {
            throw new UncheckedIOException("写出评测报告失败", e);
        }
    }

    private String row(String name, String current, String baseline) {
        return "| " + name + " | " + current + " | " + baseline + " |\n";
    }

    private String baselineValue(RagEvalBaseline baseline, boolean recall) {
        if (baseline == null) {
            return "（首次运行）";
        }
        return metric(recall ? baseline.recallAt5() : baseline.mrrAt5());
    }

    private String metric(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

}
