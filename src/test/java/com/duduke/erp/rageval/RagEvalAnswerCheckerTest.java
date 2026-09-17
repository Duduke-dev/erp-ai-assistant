package com.duduke.erp.rageval;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 答案检查单测。
 *
 * <h3>重点在「不该判违规的别判」</h3>
 * 门禁如果动不动就判定模型答错，人会习惯性忽略它。
 * 因此这里一半用例专门盯着否定句与谨慎句——它们最容易被子串匹配误伤。
 */
class RagEvalAnswerCheckerTest {

    private final RagEvalAnswerChecker checker = new RagEvalAnswerChecker();

    @Test
    @DisplayName("关键事实命中按子串统计，忽略空白与标点")
    void countsKeyFactsIgnoringSpacing() {
        RagEvalAnswerChecker.RagEvalAnswerCheck check = this.checker.check(
                "主轴最高转速为 4500 转每分钟，功率 11 千瓦。",
                List.of("4500 转每分钟", "11 千瓦", "3200 千克"), List.of());

        assertThat(check.matchedKeyFactCount()).isEqualTo(2);
        assertThat(check.totalKeyFactCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("紧邻否定词的禁止短语不算错误结论")
    void negatedForbiddenPhraseIsNotViolation() {
        RagEvalAnswerChecker.RagEvalAnswerCheck check = this.checker.check(
                "该设备不能空载运行，必须先装夹工件。",
                List.of(), List.of("空载运行"));

        assertThat(check.criticalFactViolations()).isEmpty();
    }

    @Test
    @DisplayName("谨慎语气里提到的禁止短语不算错误结论")
    void uncertainForbiddenPhraseIsNotViolation() {
        RagEvalAnswerChecker.RagEvalAnswerCheck check = this.checker.check(
                "资料中无法确认该机型支持空载运行。",
                List.of(), List.of("空载运行"));

        assertThat(check.criticalFactViolations()).isEmpty();
    }

    @Test
    @DisplayName("给出错误结论时判违规")
    void assertedForbiddenPhraseIsViolation() {
        RagEvalAnswerChecker.RagEvalAnswerCheck check = this.checker.check(
                "该机型可以空载运行。",
                List.of(), List.of("空载运行"));

        assertThat(check.criticalFactViolations()).containsExactly("空载运行");
    }

    @Test
    @DisplayName("谨慎语气之后转入断言，仍判违规")
    void uncertaintyFollowedByAssertionIsViolation() {
        RagEvalAnswerChecker.RagEvalAnswerCheck check = this.checker.check(
                "资料中无法确认，但实际上该机型可以空载运行。",
                List.of(), List.of("空载运行"));

        assertThat(check.criticalFactViolations())
                .as("「无法确认」后面出现了「但实际上」，说明模型已经给出了结论")
                .containsExactly("空载运行");
    }

    @Test
    @DisplayName("违规短语在回答里重复出现且后一次是肯定语气时判违规")
    void repeatedAssertionAfterUncertaintyIsViolation() {
        RagEvalAnswerChecker.RagEvalAnswerCheck check = this.checker.check(
                "无法确认是否支持空载运行。实测空载运行正常。",
                List.of(), List.of("空载运行"));

        assertThat(check.criticalFactViolations()).containsExactly("空载运行");
    }

    @Test
    @DisplayName("拒答表述被识别为未作答")
    void detectsRefusal() {
        assertThat(this.checker.isRefusal("资料中没有找到相关信息，无法回答该问题。")).isTrue();
        assertThat(this.checker.isRefusal("标准产品交期为 15 个工作日。")).isFalse();
    }

    @Test
    @DisplayName("空回答也算拒答：对不可答用例来说不回答不是胡编")
    void emptyAnswerCountsAsRefusal() {
        assertThat(this.checker.isRefusal("")).isTrue();
        assertThat(this.checker.isRefusal(null)).isTrue();
    }

}
