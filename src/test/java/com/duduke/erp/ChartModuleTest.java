package com.duduke.erp;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.duduke.erp.service.chart.BusinessToolResult;
import com.duduke.erp.service.chart.ChartCompiler;
import com.duduke.erp.service.chart.ChartPlan;
import com.duduke.erp.service.chart.ChartPlanToolCallback;
import com.duduke.erp.service.chart.ChartSpec;
import com.duduke.erp.service.chart.ChartType;
import com.duduke.erp.service.chart.ToolResultRecorder;
import com.duduke.erp.service.tool.ToolNames;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4.2 图表模块核心的验证。
 * <p>
 * 三条最该盯住的：
 * <ol>
 *   <li><b>归属校验</b>：租户或会话对不上时必须返回空——画错租户的数据是跨租户泄漏，且不报错；</li>
 *   <li><b>字段绑定</b>：分类列取非数值列、数值列取数值列，模型全程不参与；</li>
 *   <li><b>类型白名单</b>：模型给出不在枚举内的类型要能明确拒绝并提示可选值。</li>
 * </ol>
 */
class ChartModuleTest {

    private final ToolResultRecorder recorder = new ToolResultRecorder();

    private final ChartCompiler compiler = new ChartCompiler();

    private final ChartPlanToolCallback callback =
            new ChartPlanToolCallback(new ObjectMapper());

    // ===== 类型 =====

    @Test
    @DisplayName("类型解析做大小写与空格归一化")
    void parsesTypeLeniently() {
        assertThat(ChartType.fromCode("bar")).isEqualTo(ChartType.BAR);
        assertThat(ChartType.fromCode(" Bar ")).isEqualTo(ChartType.BAR);
        assertThat(ChartType.fromCode("HEATMAP")).isEqualTo(ChartType.HEATMAP);
    }

    @Test
    @DisplayName("未支持的类型解析为 null（由调用方拒绝）")
    void unknownTypeResolvesToNull() {
        assertThat(ChartType.fromCode("sunburst")).isNull();
        assertThat(ChartType.fromCode(null)).isNull();
    }

    @Test
    @DisplayName("只开放 8 种图表")
    void exposesExactlyEightTypes() {
        assertThat(ChartType.values()).hasSize(8);
    }

    // ===== 结果暂存与归属校验 =====

    @Test
    @DisplayName("归属一致时可取回结果")
    void returnsResultsWhenOwnerMatches() {
        this.recorder.record("t1", "DEMO", "c1", "getInventory", rows());

        assertThat(this.recorder.getResults("t1", "DEMO", "c1")).hasSize(1);
    }

    @Test
    @DisplayName("租户对不上时拒绝返回（防跨租户泄漏）")
    void rejectsWhenTenantMismatch() {
        this.recorder.record("t2", "DEMO", "c1", "getInventory", rows());

        assertThat(this.recorder.getResults("t2", "OTHER", "c1"))
                .as("租户不同绝不能拿到数据")
                .isEmpty();
    }

    @Test
    @DisplayName("会话对不上时拒绝返回（防串档）")
    void rejectsWhenConversationMismatch() {
        this.recorder.record("t3", "DEMO", "c1", "getInventory", rows());

        assertThat(this.recorder.getResults("t3", "DEMO", "c-other")).isEmpty();
    }

    @Test
    @DisplayName("归属信息缺失时拒绝返回（来源不明的数据不画）")
    void rejectsWhenOwnerUnknown() {
        this.recorder.record("t4", null, null, "getInventory", rows());

        assertThat(this.recorder.getResults("t4", "DEMO", "c1")).isEmpty();
    }

    @Test
    @DisplayName("空结果不登记")
    void ignoresEmptyRows() {
        this.recorder.record("t5", "DEMO", "c1", "getInventory", List.of());

        assertThat(this.recorder.getResults("t5", "DEMO", "c1")).isEmpty();
    }

    @Test
    @DisplayName("clear 之后取不到")
    void clearedTraceReturnsNothing() {
        this.recorder.record("t6", "DEMO", "c1", "getInventory", rows());
        this.recorder.clear("t6");

        assertThat(this.recorder.getResults("t6", "DEMO", "c1")).isEmpty();
    }

    // ===== 编译 =====

    @Test
    @DisplayName("非数值列作分类、数值列作数值")
    void bindsCategoryAndValueColumns() {
        ChartSpec spec = this.compiler.compile(new ChartPlan(ChartType.BAR, "库存"),
                List.of(new BusinessToolResult("getInventory", rows())));

        assertThat(spec).isNotNull();
        assertThat(spec.type()).isEqualTo("bar");
        assertThat(spec.title()).isEqualTo("库存");
        assertThat(spec.categories()).containsExactly("球阀", "离心泵");
        assertThat(spec.series()).hasSize(1);
        assertThat(spec.series().get(0).values())
                .containsExactly(new BigDecimal("180"), new BigDecimal("20"));
    }

    @Test
    @DisplayName("标题为空时回退为数值列名")
    void fallsBackToValueColumnAsTitle() {
        ChartSpec spec = this.compiler.compile(new ChartPlan(ChartType.LINE, null),
                List.of(new BusinessToolResult("getInventory", rows())));

        assertThat(spec).isNotNull();
        assertThat(spec.title()).isEqualTo("quantity");
    }

    @Test
    @DisplayName("没有数值列时不画图（返回 null 而非画无意义的图）")
    void returnsNullWhenNoNumericColumn() {
        List<Map<String, Object>> rows = List.of(Map.of("name", "球阀"), Map.of("name", "离心泵"));

        assertThat(this.compiler.compile(new ChartPlan(ChartType.BAR, "x"),
                List.of(new BusinessToolResult("t", rows)))).isNull();
    }

    @Test
    @DisplayName("无可编译数据（无结果/空方案）时返回 null")
    void returnsNullWithoutData() {
        assertThat(this.compiler.compile(null, List.of())).isNull();
        assertThat(this.compiler.compile(new ChartPlan(ChartType.BAR, "x"), List.of())).isNull();
    }

    @Test
    @DisplayName("仪表盘只取第一个数值")
    void gaugeTakesSingleValue() {
        ChartSpec spec = this.compiler.compile(new ChartPlan(ChartType.GAUGE, "达成率"),
                List.of(new BusinessToolResult("getInventory", rows())));

        assertThat(spec).isNotNull();
        assertThat(spec.categories()).hasSize(1);
        assertThat(spec.series().get(0).values()).hasSize(1);
    }

    // ===== 方案 Tool =====

    @Test
    @DisplayName("合法输入登记方案，并可从 traceId 取回")
    void recordsPlanByTraceId() {
        ToolContext context = new ToolContext(
                Map.of(com.duduke.erp.service.tool.trace.ToolTraceKeys.TRACE_ID, "trace-x"));

        String receipt = this.callback.call("{\"type\":\"pie\",\"title\":\"占比\"}", context);

        assertThat(receipt).contains("已记录");
        assertThat(this.callback.planOf("trace-x"))
                .isEqualTo(new ChartPlan(ChartType.PIE, "占比"));
    }

    @Test
    @DisplayName("未支持的类型被拒绝，并提示可选值（模型可据此改正）")
    void rejectsUnsupportedTypeWithHint() {
        String receipt = this.callback.call("{\"type\":\"sunburst\"}", null);

        assertThat(receipt).contains("不支持").contains("bar");
    }

    @Test
    @DisplayName("无 traceId 时拒绝登记，不留下无人认领的方案")
    void rejectsWhenTraceIdMissing() {
        String receipt = this.callback.call("{\"type\":\"bar\"}", null);

        assertThat(receipt).contains("无法登记");
        assertThat(this.callback.planOf(null)).isNull();
    }

    @Test
    @DisplayName("Tool 名使用系统保留的图表名")
    void usesReservedChartToolName() {
        assertThat(this.callback.getToolDefinition().name()).isEqualTo(ToolNames.CHART_PLAN);
        assertThat(this.callback.getToolDefinition().inputSchema())
                .as("必须关闭额外属性，模型只能给 type 与 title")
                .contains("additionalProperties")
                .contains("false");
    }

    private static List<Map<String, Object>> rows() {
        return List.of(
                Map.of("product_name", "球阀", "quantity", 180),
                Map.of("product_name", "离心泵", "quantity", 20));
    }

}
