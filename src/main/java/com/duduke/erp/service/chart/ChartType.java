package com.duduke.erp.service.chart;

import java.util.Arrays;

/**
 * 支持的图表类型。
 * <p>
 * 相对参考实现（23 种）<b>收敛为 8 种</b>：sunburst / treemap / sankey / gantt /
 * bullet / parallel / radar / waterfall / boxplot / histogram / liquid-fill 等
 * 各带一套编译与校验分支，维护成本与收益严重不成正比。
 * <p>
 * 保留的 8 种覆盖了 ERP 问答的绝大多数场景：
 * 比较（bar）、趋势（line/area）、占比（pie/funnel）、
 * 分布（scatter）、密度（heatmap）、达成率（gauge）。
 * <p>
 * 枚举值即下发给前端的协议字面量，<b>不含中文</b>——前端按字面量渲染，
 * 加中文会让协议变成需要维护第二份映射的双份真相。
 */
public enum ChartType {

    BAR("bar"),
    LINE("line"),
    PIE("pie"),
    AREA("area"),
    SCATTER("scatter"),
    HEATMAP("heatmap"),
    FUNNEL("funnel"),
    GAUGE("gauge");

    private final String code;

    ChartType(String code) {
        this.code = code;
    }

    public String code() {
        return this.code;
    }

    /**
     * 按协议字面量解析；无法识别返回 null 由调用方决定如何拒绝。
     * <p>
     * 大小写与空格做归一化：模型偶尔输出 {@code "Bar "} 这类形式，
     * 直接拒绝会让一次正常的图表请求白白失败。
     */
    public static ChartType fromCode(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.code.equals(normalized))
                .findFirst()
                .orElse(null);
    }

    /** 供模型看的清单，用于 Tool 描述与错误提示 */
    public static String supportedCodes() {
        return Arrays.stream(values())
                .map(ChartType::code)
                .reduce((left, right) -> left + " / " + right)
                .orElse("");
    }

}
