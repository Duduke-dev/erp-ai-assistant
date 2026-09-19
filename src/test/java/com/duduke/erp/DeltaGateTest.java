package com.duduke.erp;

import java.util.List;

import com.duduke.erp.controller.ChatController;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 首轮缓冲门控（{@link ChatController.DeltaGate}）的单元测试。
 *
 * <h3>为什么要单独测它</h3>
 * 它是**阻断型防线**：判断错的两个方向代价不对称——
 * <ul>
 *   <li>该拦没拦 → 用户看到编造的数字（原来的问题，至少能靠 warning 提示）；</li>
 *   <li><b>不该拦却拦了 → 正常回答被整段吞掉</b>，用户只收到一句"没查到数据"，
 *       而问题本来是有答案的。这一侧的代价更高，所以「未启用时完全直通」
 *       和「一旦放行就不再缓冲」这两条必须被钉死。</li>
 * </ul>
 * 做成 public static 嵌套类正是为了能脱离 SSE 环境直接测——端到端无法稳定构造
 * 「模型不调工具」的场景（见 {@code ChatController} 里的说明）。
 */
class DeltaGateTest {

    @Test
    @DisplayName("未启用门控时完全直通：不缓冲、直接视为已放行")
    void passThroughWhenDisabled() {
        ChatController.DeltaGate gate = new ChatController.DeltaGate(false);

        assertThat(gate.shouldBuffer()).isFalse();
        assertThat(gate.isReleased()).isTrue();
        // 未启用时即便调用 buffer 也不该影响判断（防御性：正常路径不会这么用）
        gate.buffer("x");
        assertThat(gate.shouldBuffer()).isFalse();
    }

    @Test
    @DisplayName("启用门控后默认缓冲，不立即下发")
    void buffersUntilReleased() {
        ChatController.DeltaGate gate = new ChatController.DeltaGate(true);

        assertThat(gate.shouldBuffer()).isTrue();
        assertThat(gate.isReleased()).isFalse();
    }

    @Test
    @DisplayName("放行时返回全部已缓冲内容，且此后不再缓冲")
    void releaseReturnsBufferedAndStopsBuffering() {
        ChatController.DeltaGate gate = new ChatController.DeltaGate(true);
        gate.buffer("第一段");
        gate.buffer("第二段");

        List<String> released = gate.release();

        assertThat(released).containsExactly("第一段", "第二段");
        assertThat(gate.isReleased()).isTrue();
        assertThat(gate.shouldBuffer()).isFalse();
        // 再放行一次应返回空——已清空，不能把同一批内容重复补发
        assertThat(gate.release()).isEmpty();
    }

    @Test
    @DisplayName("丢弃时返回条数，且视为结束（不再缓冲）")
    void discardReportsCountAndEnds() {
        ChatController.DeltaGate gate = new ChatController.DeltaGate(true);
        gate.buffer("a");
        gate.buffer("b");
        gate.buffer("c");

        int dropped = gate.discard();

        assertThat(dropped).isEqualTo(3);
        assertThat(gate.isReleased()).isTrue();
        assertThat(gate.shouldBuffer()).isFalse();
        // 丢弃后内容不可再取回：这是「不给用户看到编造内容」的保证
        assertThat(gate.release()).isEmpty();
    }

    @Test
    @DisplayName("没有任何缓冲时放行/丢弃都是安全的空操作")
    void emptyGateIsSafe() {
        ChatController.DeltaGate gate = new ChatController.DeltaGate(true);

        assertThat(gate.release()).isEmpty();

        ChatController.DeltaGate another = new ChatController.DeltaGate(true);
        assertThat(another.discard()).isZero();
    }

    @Test
    @DisplayName("多线程缓冲不丢内容（synchronized 生效）")
    void concurrentBufferingDoesNotLoseContent() throws Exception {
        ChatController.DeltaGate gate = new ChatController.DeltaGate(true);
        int threads = 8;
        int perThread = 50;
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                for (int j = 0; j < perThread; j++) {
                    gate.buffer("x");
                }
            });
            workers[i].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }

        assertThat(gate.release()).hasSize(threads * perThread);
    }

}
