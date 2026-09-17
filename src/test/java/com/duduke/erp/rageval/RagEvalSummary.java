package com.duduke.erp.rageval;

/**
 * 聚合指标。
 *
 * <h3>为什么没有「总分」</h3>
 * 一个加权总分会把「召回到没召回」和「越没越界」混成一个数，
 * 回归时看不出到底是哪一项坏了。这里保持分列，门禁逐项判定。
 */
public record RagEvalSummary(
        int caseCount,
        double recallAt5,
        double mrrAt5,
        double emptyRetrievalRate,
        double refusalRate,
        double keyFactHitRate,
        int boundaryViolationCount,
        int criticalFactViolationCount,
        long latencyP50,
        long latencyP95) {
}
