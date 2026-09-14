package com.duduke.erp;

import com.duduke.erp.service.AssistantAnswerSanitizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 答案净化器的验证。
 * <p>
 * 这类纯文本规则最容易在「宁可漏删还是宁可误删」上跑偏，
 * 所以这里的用例刻意覆盖了<b>不该删</b>的情况——误删业务正文比漏删旁白严重得多。
 */
class AssistantAnswerSanitizerTest {

    private final AssistantAnswerSanitizer sanitizer = new AssistantAnswerSanitizer();

    @Test
    @DisplayName("删除以「让我」开头的内部旁白段落，保留业务结论")
    void stripsInternalNarrationParagraph() {
        String raw = "让我先查询销售订单数据。\n\n销售额为 55000 元。";

        assertThat(this.sanitizer.sanitize(raw))
                .isEqualTo("销售额为 55000 元。");
    }

    @Test
    @DisplayName("普通业务回答原样保留，不做任何改动")
    void keepsPlainBusinessAnswer() {
        String raw = "主仓库存共 180 件球阀，低于安全库存的是离心泵。";

        assertThat(this.sanitizer.sanitize(raw)).isEqualTo(raw);
    }

    @Test
    @DisplayName("存在边界标记时只取标记之后的内容")
    void keepsOnlyContentAfterFinalAnswerMarker() {
        String raw = "我需要先调用工具查询。\n" + AssistantAnswerSanitizer.FINAL_ANSWER_MARKER
                + "\n最终答案：库存 180 件。";

        assertThat(this.sanitizer.sanitize(raw)).isEqualTo("最终答案：库存 180 件。");
    }

    @Test
    @DisplayName("用 --- 分隔过程与结论时取最后一段")
    void takesFinalSectionAfterSeparator() {
        String raw = "让我查询一下。\n\n---\n\n查询结果：共 3 条。";

        assertThat(this.sanitizer.sanitize(raw)).isEqualTo("查询结果：共 3 条。");
    }

    @Test
    @DisplayName("英文查询旁白后接中文正文时，只保留中文正文")
    void stripsLeadingEnglishNarration() {
        String raw = "Let me query the inventory data. 主仓库存共 180 件。";

        assertThat(this.sanitizer.sanitize(raw)).isEqualTo("主仓库存共 180 件。");
    }

    @Test
    @DisplayName("不误删：正文里出现「让我」但并非段首时不删")
    void doesNotStripWhenPhraseIsMidSentence() {
        String raw = "这份报表让我确认了库存偏低。";

        assertThat(this.sanitizer.sanitize(raw)).isEqualTo(raw);
    }

    @Test
    @DisplayName("不误删：「我们的」这类以「我」开头但不是旁白的段落")
    void doesNotStripBusinessTextStartingWithWo() {
        String raw = "我们的库存周转率是本季度的关键指标。";

        assertThat(this.sanitizer.sanitize(raw)).isEqualTo(raw);
    }

    @Test
    @DisplayName("空输入返回空串")
    void returnsEmptyForBlankInput() {
        assertThat(this.sanitizer.sanitize(null)).isEmpty();
        assertThat(this.sanitizer.sanitize("")).isEmpty();
        assertThat(this.sanitizer.sanitize("   \n  ")).isEmpty();
    }

    @Test
    @DisplayName("整段都是旁白时不应留下空行残留")
    void handlesAllNarrationContent() {
        String raw = "让我先查询数据。";

        assertThat(this.sanitizer.sanitize(raw)).isEmpty();
    }

}
