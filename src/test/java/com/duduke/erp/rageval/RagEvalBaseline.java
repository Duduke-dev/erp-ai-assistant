package com.duduke.erp.rageval;

/**
 * 版本基线：只记录用于回归判定的指标。
 *
 * <h3>为什么只存两项</h3>
 * 基线是用来回答「这次改动有没有让检索变差」的，能稳定复现的只有召回类指标。
 * 延迟受机器负载影响、关键事实命中受模型输出波动影响，把它们放进基线
 * 会让门禁随机变红——那时人会习惯性忽略红灯，门禁等于失效。
 *
 * @param recallAt5 基线 Recall@5
 * @param mrrAt5    基线 MRR@5
 */
public record RagEvalBaseline(double recallAt5, double mrrAt5) {
}
