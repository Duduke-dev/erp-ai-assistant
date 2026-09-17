package com.duduke.erp.rageval;

import java.util.List;

/**
 * 单条评测用例。
 *
 * @param caseId           稳定用例 ID（报告与基线按它对齐）
 * @param question         提问
 * @param expectedSources  期望召回的文档来源（fixture 文件名）
 * @param answerable       知识库是否足以回答；false 的用例用于检验「会不会胡编」
 * @param keyFacts         答案里应当出现的关键事实（子串匹配）
 * @param forbiddenPhrases 禁止出现的关键错误结论
 * @param category         分类，便于报告分组
 */
public record RagEvalCase(
        String caseId,
        String question,
        List<String> expectedSources,
        boolean answerable,
        List<String> keyFacts,
        List<String> forbiddenPhrases,
        String category) {
}
