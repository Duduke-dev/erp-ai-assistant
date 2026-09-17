package com.duduke.erp.rageval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 确定性答案检查：关键事实命中 + 禁止结论识别。不调用模型。
 *
 * <h3>为什么要处理否定与谨慎语义</h3>
 * 直接子串匹配会把「该设备<b>不能</b>空载运行」里的「空载运行」判成命中，
 * 也会把「资料中<b>无法确认</b>是否支持批量导出」判成给出了错误结论——
 * 两种情况都会把正确答案判成错误答案，门禁就变成了噪声源。
 * 因此命中前先看前缀：紧邻否定词、或仍在谨慎语境内且没有转入断言的，都算豁免。
 *
 * <h3>这是规则式而非模型判定</h3>
 * 与 Query Rewrite 同一取舍：模型判定要额外一次调用、结果不稳定且难以回归，
 * 而这里的规则是确定性的——同样的回答永远给出同样的判定，才能做门禁。
 */
public class RagEvalAnswerChecker {

    /**
     * 紧邻这些词时，短语处于被否定的位置。
     * <p>
     * 含单字否定「不」「未」「非」是必需的：中文里「不可以跨租户查询」的否定词就是「不」，
     * 「可」属于禁止短语本身，靠双字词表必然漏判——这正是首轮 v2 评测把
     * 「**不**可以跨租户查询」判成错误结论的原因。
     * 单字不会过宽：判定只取短语**紧邻**的前缀，且已限定在同一分句内
     * （见 {@link #CLAUSE_BOUNDARY}），"…不清楚，可以跨租户查询"这类不会被误豁免。
     */
    private static final List<String> NEGATION_MARKERS = List.of(
            "并非", "不是", "不能", "不可", "禁止", "不允许", "无法", "不再", "未能", "没有",
            "不", "未", "非");

    /**
     * 分句边界：否定与谨慎语义都只在本分句内生效。
     * <p>
     * 必须先切句再匹配，因为下面的匹配是在「去掉标点后的文本」上做的——
     * 一旦先规范化，句子边界就消失了，「不能。可以跨租户查询。」会被读成一整句，
     * 后半个肯定句被前句的否定词豁免掉。
     */
    private static final String CLAUSE_BOUNDARY = "[。！？；;，,：:、\\r\\n]+";

    /** 表示资料不足以确认结论的谨慎词 */
    private static final List<String> UNCERTAINTY_MARKERS = List.of(
            "无法确认", "不能确定", "尚未确认", "无法判断", "不能判断", "未能找到", "没有找到", "未提及");

    /** 出现这些词说明已经脱离谨慎语气、转入实际结论 */
    private static final List<String> ASSERTION_MARKERS = List.of(
            "但", "不过", "然而", "实际上", "事实上", "却", "确实", "可以确定", "能够确定");

    /** 拒答标志：这些表述说明模型没有给出实质答案 */
    private static final List<String> REFUSAL_MARKERS = List.of(
            "没有找到", "未找到", "未检索到", "没有检索到", "无法回答", "不能回答",
            "资料中没有", "资料中未", "知识库中没有", "没有相关信息", "未提及", "无法确认", "不能确定");

    /**
     * 检查一条回答。
     *
     * @param answer           模型回答
     * @param keyFacts         期望出现的关键事实
     * @param forbiddenPhrases 禁止出现的关键错误结论
     */
    public RagEvalAnswerCheck check(String answer, List<String> keyFacts,
                                    List<String> forbiddenPhrases) {
        String normalized = normalize(answer);
        List<String> facts = keyFacts == null ? List.of() : keyFacts;
        int matched = 0;
        for (String fact : facts) {
            String target = normalize(fact);
            if (!target.isEmpty() && normalized.contains(target)) {
                matched++;
            }
        }

        List<String> violations = new ArrayList<>();
        for (String phrase : forbiddenPhrases == null ? List.<String>of() : forbiddenPhrases) {
            String target = normalize(phrase);
            if (!target.isEmpty() && containsForbiddenAssertion(answer, target)) {
                // 报告里保留原始短语，便于直接定位到底是哪条结论错了
                violations.add(phrase);
            }
        }
        return new RagEvalAnswerCheck(matched, facts.size(), List.copyOf(violations));
    }

    /**
     * 判断回答是否属于拒答（未给出实质答案）。
     * <p>
     * 空回答也算拒答：对「知识库里没有答案」的用例来说，
     * 不回答不算胡编，把它算成违规会把正确行为判成错误。
     */
    public boolean isRefusal(String answer) {
        String normalized = normalize(answer);
        if (normalized.isEmpty()) {
            return true;
        }
        return REFUSAL_MARKERS.stream()
                .map(this::normalize)
                .anyMatch(normalized::contains);
    }

    /**
     * 短语是否以肯定语义出现（未被否定、且不在谨慎语境内）。
     * <p>
     * 逐分句判断：先按标点切句，再在「该句规范化后的文本」里找短语。
     * 这样否定词与谨慎词的作用范围天然被限制在同一句内，
     * 不需要（也不应该）跨句继承。
     *
     * @param answer           模型原始回答（保留标点，切句依赖它）
     * @param normalizedPhrase 已规范化的禁止短语
     */
    private boolean containsForbiddenAssertion(String answer, String normalizedPhrase) {
        if (answer == null) {
            return false;
        }
        for (String clause : answer.split(CLAUSE_BOUNDARY)) {
            String normalizedClause = normalize(clause);
            int index = normalizedClause.indexOf(normalizedPhrase);
            while (index >= 0) {
                String prefix = normalizedClause.substring(0, index);
                if (!isNegated(prefix) && !isInsideUncertaintyScope(prefix, normalizedPhrase)) {
                    return true;
                }
                index = normalizedClause.indexOf(normalizedPhrase, index + normalizedPhrase.length());
            }
        }
        return false;
    }

    private boolean isNegated(String prefix) {
        return NEGATION_MARKERS.stream().anyMatch(prefix::endsWith);
    }

    private boolean isInsideUncertaintyScope(String prefix, String normalizedPhrase) {
        int uncertaintyIndex = lastIndexOfAny(prefix, UNCERTAINTY_MARKERS);
        if (uncertaintyIndex < 0) {
            return false;
        }
        String scope = prefix.substring(uncertaintyIndex);
        // 同一谨慎范围里已经出现过该短语，说明后一次是重复表述，不再豁免
        return !scope.contains(normalizedPhrase)
                && ASSERTION_MARKERS.stream().noneMatch(scope::contains);
    }

    private int lastIndexOfAny(String value, List<String> markers) {
        int latest = -1;
        for (String marker : markers) {
            latest = Math.max(latest, value.lastIndexOf(marker));
        }
        return latest;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}\\p{P}]+", "");
    }

    /**
     * 单条回答的检查结果。
     *
     * @param matchedKeyFactCount    命中的关键事实数
     * @param totalKeyFactCount      关键事实总数
     * @param criticalFactViolations 命中的禁止短语（原始文本）
     */
    public record RagEvalAnswerCheck(
            int matchedKeyFactCount,
            int totalKeyFactCount,
            List<String> criticalFactViolations) {
    }

}
