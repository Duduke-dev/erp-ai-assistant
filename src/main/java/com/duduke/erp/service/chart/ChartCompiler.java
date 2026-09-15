package com.duduke.erp.service.chart;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

/**
 * 把「图表方案 + 本轮业务结果」编译成前端协议 {@link ChartSpec}。
 * <p>
 * <b>字段绑定、聚合、取值全部在后端完成</b>——这是「模型只输出 type + title」能成立的前提。
 * 模型不知道 Tool 返回哪些列，让它指定字段等于让它猜；这里按数据形态推断：
 * <ol>
 *   <li>分类列（X 轴）：命中的第一个<b>非数值</b>列（如产品名、月份、客户）；</li>
 *   <li>数值列（Y 轴）：命中的第一个<b>数值</b>列（如数量、金额）。</li>
 * </ol>
 * 推断不出数值列时返回 {@code null}——此时不画图，而不是画一张没有意义的图。
 *
 * <h3>条数上限</h3>
 * 超过 {@value #MAX_POINTS} 行只取前 {@value #MAX_POINTS} 行：
 * 几十上百个刻度的图在对话里看不清，而且会把 prompt 与响应撑得很大。
 */
@Slf4j
@Component
public class ChartCompiler {

    /** 单图最多的数据点 */
    private static final int MAX_POINTS = 50;

    /**
     * 编译图表。
     *
     * @param plan    已校验的图表方案
     * @param results 本轮业务结果（来自 {@link ToolResultRecorder}）
     * @return 前端协议；无可用数据或无法绑定字段时返回 {@code null}
     */
    public ChartSpec compile(ChartPlan plan, List<BusinessToolResult> results) {
        if (plan == null || plan.type() == null || results == null || results.isEmpty()) {
            return null;
        }
        for (BusinessToolResult result : results) {
            ChartSpec spec = compileOne(plan, result);
            if (spec != null) {
                return spec;
            }
        }
        log.debug("本轮没有可编译成图表的数据：planType={}", plan.type().code());
        return null;
    }

    private ChartSpec compileOne(ChartPlan plan, BusinessToolResult result) {
        List<Map<String, Object>> rows = result.rows();
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> limited =
                rows.size() > MAX_POINTS ? rows.subList(0, MAX_POINTS) : rows;

        String categoryColumn = null;
        String valueColumn = null;
        for (String column : limited.get(0).keySet()) {
            Object sample = limited.get(0).get(column);
            if (valueColumn == null && toBigDecimal(sample) != null) {
                valueColumn = column;
            }
            else if (categoryColumn == null) {
                categoryColumn = column;
            }
        }
        if (valueColumn == null) {
            return null;
        }
        // 全是数值列时（如只有数量一列）用行号当分类，否则图没有横轴
        boolean useRowIndex = categoryColumn == null;

        List<String> categories = new ArrayList<>();
        List<BigDecimal> values = new ArrayList<>();
        for (int index = 0; index < limited.size(); index++) {
            Map<String, Object> row = limited.get(index);
            BigDecimal value = toBigDecimal(row.get(valueColumn));
            if (value == null) {
                continue;
            }
            categories.add(useRowIndex ? String.valueOf(index + 1)
                    : String.valueOf(row.get(categoryColumn)));
            values.add(value);
        }
        if (values.isEmpty()) {
            return null;
        }

        String title = plan.title() == null || plan.title().isBlank()
                ? valueColumn : plan.title().trim();
        // 仪表盘只看一个数：取首值，多个刻度没有语义
        if (plan.type() == ChartType.GAUGE) {
            return new ChartSpec(plan.type().code(), title,
                    List.of(categories.get(0)),
                    List.of(new ChartSpec.Series(title, List.of(values.get(0)))));
        }
        return new ChartSpec(plan.type().code(), title, List.copyOf(categories),
                List.of(new ChartSpec.Series(title, List.copyOf(values))));
    }

    /**
     * 转数值。无法解析返回 null（而非抛异常）——
     * 一行里有脏数据不该让整张图失败，跳过即可。
     */
    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof java.math.BigInteger bigInteger) {
            return new BigDecimal(bigInteger);
        }
        // 整型走 longValue 而不是 doubleValue：
        // ① 不会产生 180.0 这种多出来的小数位；
        // ② 金额类大整数经 double 中转会有精度损失——ERP 里这是不能接受的
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return new BigDecimal(value.toString().trim());
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

}
