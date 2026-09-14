package com.duduke.erp;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.duduke.erp.service.BusinessDataTurnGuard;
import com.duduke.erp.service.tool.trace.ToolCallRecord;
import com.duduke.erp.service.tool.trace.ToolCallRecorder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 业务数据守卫的验证。
 * <p>
 * 关键是「本轮有没有取得业务数据」的判定口径：
 * <b>只有「调用成功且结果非空」才算拿到数据</b>——
 * 调用失败、或查到 0 行，模型手上同样没有本轮数据，都该重试。
 * 这三条各有一个用例盯着，避免将来被简化成「调过 Tool 就算」。
 */
class BusinessDataTurnGuardTest {

    private final ToolCallRecorder recorder = new ToolCallRecorder();

    private final BusinessDataTurnGuard guard = new BusinessDataTurnGuard(this.recorder);

    // ===== 意图识别 =====

    @Test
    @DisplayName("auto 模式下问业务数据需要本轮数据")
    void requiresDataForBusinessQuestion() {
        assertThat(this.guard.requiresCurrentBusinessData("auto", "查询主仓库存")).isTrue();
        assertThat(this.guard.requiresCurrentBusinessData("auto", "统计本月销售额")).isTrue();
    }

    @Test
    @DisplayName("闲聊或纯概念提问不需要本轮业务数据")
    void doesNotRequireDataForChitchat() {
        assertThat(this.guard.requiresCurrentBusinessData("auto", "你好")).isFalse();
        assertThat(this.guard.requiresCurrentBusinessData("auto", "谢谢你")).isFalse();
    }

    @Test
    @DisplayName("只有业务对象词但没有查询意图时不触发（避免误伤）")
    void requiresBothBusinessTermAndDataIntent() {
        // 「客户」是业务词，但整句是在讲道理而非要数据
        assertThat(this.guard.requiresCurrentBusinessData("auto", "客户至上很重要")).isFalse();
    }

    @Test
    @DisplayName("data 模式恒需要，knowledge 模式恒不需要")
    void modeOverrides() {
        assertThat(this.guard.requiresCurrentBusinessData("data", "任意问题")).isTrue();
        assertThat(this.guard.requiresCurrentBusinessData("knowledge", "查询库存")).isFalse();
    }

    // ===== 本轮业务数据判定 =====

    @Test
    @DisplayName("调用成功且结果非空 → 已取得业务数据")
    void successWithRowsCountsAsBusinessResult() {
        String traceId = "t-success";
        this.recorder.record(traceId, record("getInventory", "success", 3));

        assertThat(this.guard.hasBusinessResult(traceId)).isTrue();
    }

    @Test
    @DisplayName("调用成功但结果为 0 行 → 不算取得业务数据")
    void successWithZeroRowsDoesNotCount() {
        String traceId = "t-empty";
        this.recorder.record(traceId, record("getInventory", "success", 0));

        assertThat(this.guard.hasBusinessResult(traceId)).isFalse();
    }

    @Test
    @DisplayName("调用失败 → 不算取得业务数据")
    void failedCallDoesNotCount() {
        String traceId = "t-error";
        this.recorder.record(traceId, record("getInventory", "error", 0));

        assertThat(this.guard.hasBusinessResult(traceId)).isFalse();
    }

    @Test
    @DisplayName("没有任何调用 → 不算取得业务数据")
    void noCallDoesNotCount() {
        assertThat(this.guard.hasBusinessResult("t-none")).isFalse();
    }

    // ===== 有界重试 =====

    @Test
    @DisplayName("本轮无业务结果时重试一次，并采用重试结果")
    void retriesOnceWhenNoBusinessResult() {
        AtomicInteger calls = new AtomicInteger();
        ChatResponse retryResponse = response("重试后的回答");

        ChatResponse result = this.guard.ensureNonStreaming(
                response("首次回答"), () -> {
                    calls.incrementAndGet();
                    return retryResponse;
                },
                "auto", "查询主仓库存", "t-retry");

        assertThat(calls.get()).as("只应重试一次，避免无限重试烧 token").isEqualTo(1);
        assertThat(result).isSameAs(retryResponse);
    }

    @Test
    @DisplayName("已有本轮业务结果时不重试")
    void doesNotRetryWhenBusinessResultPresent() {
        String traceId = "t-has-data";
        this.recorder.record(traceId, record("getInventory", "success", 5));
        AtomicInteger calls = new AtomicInteger();
        ChatResponse initial = response("首次回答");

        ChatResponse result = this.guard.ensureNonStreaming(
                initial, () -> {
                    calls.incrementAndGet();
                    return response("重试");
                },
                "auto", "查询主仓库存", traceId);

        assertThat(calls.get()).isZero();
        assertThat(result).isSameAs(initial);
    }

    @Test
    @DisplayName("问题不需要业务数据时不重试（闲聊不该触发重试）")
    void doesNotRetryWhenQuestionNeedsNoData() {
        AtomicInteger calls = new AtomicInteger();
        ChatResponse initial = response("你好，有什么可以帮你？");

        ChatResponse result = this.guard.ensureNonStreaming(
                initial, () -> {
                    calls.incrementAndGet();
                    return response("重试");
                },
                "auto", "你好", "t-chat");

        assertThat(calls.get()).isZero();
        assertThat(result).isSameAs(initial);
    }

    @Test
    @DisplayName("重试未返回响应时直接失败，不留空回答")
    void failsWhenRetryReturnsNothing() {
        assertThatThrownBy(() -> this.guard.ensureNonStreaming(
                response("首次"), () -> null, "auto", "查询库存", "t-null"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重试未返回模型响应");
    }

    @Test
    @DisplayName("重试提示词明确禁止复用历史数字")
    void retryPromptForbidsReusingHistory() {
        String prompt = this.guard.retryQuestion("查询主仓库存");

        assertThat(prompt).contains("不得继续使用历史回答中的数字")
                .contains("必须")
                .contains("查询主仓库存");
    }

    // ===== 辅助 =====

    private static ToolCallRecord record(String toolName, String status, int resultCount) {
        return new ToolCallRecord(toolName, "code", "{}", status, resultCount, 12L, null);
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

}
