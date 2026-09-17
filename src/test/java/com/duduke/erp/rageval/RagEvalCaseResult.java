package com.duduke.erp.rageval;

import java.util.List;

/**
 * 单条用例的实测结果。
 *
 * @param retrievedSources        实际召回的文档来源（按相似度降序）
 * @param boundaryViolationCount  越界条数：召回了不属于目标范围的文档
 * @param answer                  模型回答，未跑答案层时为空
 * @param criticalFactViolations  命中的禁止短语
 * @param refused                 是否拒答（仅对不可答用例有意义）
 * @param error                   执行错误；非空即视为用例失败
 */
public record RagEvalCaseResult(
        String caseId,
        boolean answerable,
        List<String> retrievedSources,
        List<String> expectedSources,
        int boundaryViolationCount,
        String answer,
        int matchedKeyFactCount,
        int totalKeyFactCount,
        List<String> criticalFactViolations,
        boolean refused,
        long latencyMs,
        String error) {
}
