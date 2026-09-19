package com.duduke.erp;

import java.util.List;

import com.duduke.erp.service.QueryRewriteService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索查询改写的验证。
 * <p>
 * 重点在<b>不该改的别改</b>：把独立问题拼上上一轮主语会引入语义噪声，
 * 让检索结果偏离用户意图——而且这种偏离看起来很正常，极难发现。
 * 所以一半用例在盯「保持原样」。
 */
class QueryRewriteServiceTest {

    // clientProvider 传 null：本用例只覆盖规则式路径（RagProperties 默认 rewrite-mode=rule），
    // 模型润色那条分支靠集成验证，不在这里调真实 API
    private final QueryRewriteService service =
            new QueryRewriteService(null, new com.duduke.erp.config.RagProperties());

    @Test
    @DisplayName("以连接词开头的短追问，拼上上一轮主语")
    void rewritesConnectorFollowUp() {
        assertThat(this.service.rewrite("那上个月呢", List.of("主仓库存情况")))
                .isEqualTo("主仓库存情况 上个月");
    }

    @Test
    @DisplayName("以「呢」结尾的短追问同样改写")
    void rewritesShortWeiFollowUp() {
        assertThat(this.service.rewrite("上个月呢", List.of("主仓库存情况")))
                .isEqualTo("主仓库存情况 上个月");
    }

    @Test
    @DisplayName("纯连接词追问（那呢）直接用锚点")
    void pureConnectorFallsBackToAnchor() {
        assertThat(this.service.rewrite("那呢", List.of("主仓库存情况")))
                .isEqualTo("主仓库存情况");
    }

    @Test
    @DisplayName("语义完整的独立问题保持原样")
    void keepsStandaloneQuestion() {
        assertThat(this.service.rewrite("查询主仓库存", List.of("上个月的销售额")))
                .isEqualTo("查询主仓库存");
    }

    @Test
    @DisplayName("以连接词开头但语义完整的长问题也保持原样（避免引入噪声）")
    void keepsLongQuestionStartingWithConnector() {
        String question = "那我想了解一下上个月主仓库的库存情况以及低库存预警";

        assertThat(this.service.rewrite(question, List.of("主仓库存情况")))
                .isEqualTo(question);
    }

    @Test
    @DisplayName("没有历史提问时不改写")
    void keepsQuestionWithoutHistory() {
        assertThat(this.service.rewrite("那上个月呢", List.of())).isEqualTo("那上个月呢");
        assertThat(this.service.rewrite("那上个月呢", null)).isEqualTo("那上个月呢");
    }

    @Test
    @DisplayName("取最近一条非空提问作为锚点")
    void usesMostRecentNonBlankAnchor() {
        assertThat(this.service.rewrite("那上个月呢", List.of("上上个问题", "主仓库存情况", "  ")))
                .isEqualTo("主仓库存情况 上个月");
    }

    @Test
    @DisplayName("空问题原样返回，不做任何拼接")
    void handlesBlankQuestion() {
        assertThat(this.service.rewrite(null, List.of("主仓库存情况"))).isNull();
        assertThat(this.service.rewrite("   ", List.of("主仓库存情况"))).isEqualTo("   ");
    }

    @Test
    @DisplayName("过长锚点被截断，避免整段上一轮问题搬进来")
    void truncatesLongAnchor() {
        String longAnchor = "一".repeat(200);

        String rewritten = this.service.rewrite("那上个月呢", List.of(longAnchor));

        assertThat(rewritten).startsWith("一".repeat(60)).endsWith("上个月");
        assertThat(rewritten.length()).isLessThan(longAnchor.length());
    }

}
