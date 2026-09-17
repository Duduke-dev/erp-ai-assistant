package com.duduke.erp.rageval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 评测指标计算，全部是纯函数——不碰模型、不碰数据库，因此可以单测。
 *
 * <h3>Recall@5 按「文档来源」而不是分片算</h3>
 * 检索返回的是一条条分片，而人关心的是「那份资料到底有没有被找出来」。
 * 同一份文档命中三个分片，只应算一次命中；按分片算会刷高指标，
 * 掩盖「只召回了同一份文档」这种实际情况。
 */
public class RagEvalMetrics {

    /** 取前 5 条，与原型的 Recall@5 口径一致 */
    private static final int TOP_K = 5;

    /**
     * 聚合全部指标。
     *
     * @param results 逐用例结果
     */
    public RagEvalSummary summarize(List<RagEvalCaseResult> results) {
        if (results == null || results.isEmpty()) {
            throw new IllegalArgumentException("评测结果不能为空");
        }
        List<RagEvalCaseResult> answerable = results.stream()
                .filter(RagEvalCaseResult::answerable)
                .toList();
        List<RagEvalCaseResult> noAnswer = results.stream()
                .filter(result -> !result.answerable())
                .toList();

        double recallAt5 = answerable.stream().mapToDouble(this::recallAt5).average().orElse(0.0);
        double mrrAt5 = answerable.stream().mapToDouble(this::mrrAt5).average().orElse(0.0);
        long emptyRetrievals = results.stream()
                .filter(result -> result.retrievedSources() == null || result.retrievedSources().isEmpty())
                .count();
        double refusalRate = ratio(noAnswer.stream().filter(RagEvalCaseResult::refused).count(),
                noAnswer.size(), 1.0);

        int totalKeyFacts = results.stream().mapToInt(RagEvalCaseResult::totalKeyFactCount).sum();
        int matchedKeyFacts = results.stream().mapToInt(RagEvalCaseResult::matchedKeyFactCount).sum();
        double keyFactHitRate = totalKeyFacts == 0 ? 1.0 : (double) matchedKeyFacts / totalKeyFacts;

        int boundaryViolations = results.stream().mapToInt(RagEvalCaseResult::boundaryViolationCount).sum();
        int criticalViolations = results.stream()
                .mapToInt(result -> result.criticalFactViolations().size())
                .sum();

        List<Long> latencies = new ArrayList<>(results.stream().map(RagEvalCaseResult::latencyMs).toList());
        latencies.sort(Long::compareTo);

        return new RagEvalSummary(results.size(), recallAt5, mrrAt5,
                ratio(emptyRetrievals, results.size(), 0.0), refusalRate, keyFactHitRate,
                boundaryViolations, criticalViolations,
                percentile(latencies, 0.50), percentile(latencies, 0.95));
    }

    /**
     * 单条用例 Recall@5：期望来源中被前 5 条召回的占比。
     *
     * @param result 用例结果
     */
    public double recallAt5(RagEvalCaseResult result) {
        Set<String> expected = new HashSet<>(safeList(result.expectedSources()));
        if (expected.isEmpty()) {
            return 0.0;
        }
        Set<String> retrieved = new HashSet<>(topFive(result.retrievedSources()));
        retrieved.retainAll(expected);
        return (double) retrieved.size() / expected.size();
    }

    /**
     * 单条用例 MRR@5：第一个命中的倒数排名。
     *
     * @param result 用例结果
     */
    public double mrrAt5(RagEvalCaseResult result) {
        Set<String> expected = new HashSet<>(safeList(result.expectedSources()));
        if (expected.isEmpty()) {
            return 0.0;
        }
        List<String> retrieved = topFive(result.retrievedSources());
        for (int index = 0; index < retrieved.size(); index++) {
            if (expected.contains(retrieved.get(index))) {
                return 1.0 / (index + 1);
            }
        }
        return 0.0;
    }

    private List<String> topFive(List<String> sources) {
        List<String> safe = safeList(sources);
        return safe.subList(0, Math.min(TOP_K, safe.size()));
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    /**
     * 比率；分母为 0 时返回 {@code emptyValue}。
     * <p>
     * 不可答用例为空时「拒答率」在数学上没有定义，这里返回 1.0 而不是 0.0——
     * 0.0 会被读成「一条都没拒答」，那是把「没有可拒答的用例」误报成缺陷。
     */
    private double ratio(long numerator, long denominator, double emptyValue) {
        return denominator == 0 ? emptyValue : (double) numerator / denominator;
    }

    /** nearest-rank 分位数，取值不插值 */
    private long percentile(List<Long> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0L;
        }
        int index = Math.max(0, (int) Math.ceil(percentile * sortedValues.size()) - 1);
        return sortedValues.get(index);
    }

}
