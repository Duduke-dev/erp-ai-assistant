package com.duduke.erp.service.chart;

import java.math.BigDecimal;
import java.util.List;

/**
 * 前端渲染协议：<b>纯数据，不含任何可执行内容</b>。
 * <p>
 * 前端按 {@code type} 选择渲染方式，读 {@code categories} 与 {@code series} 画图，
 * 不执行任何函数、不做任何字段推断。这样即使模型输出被污染，
 * 最坏也只是画出一张奇怪的图，而不会被注入脚本。
 *
 * @param type       图表类型字面量（见 {@link ChartType#code()}）
 * @param title      图表标题
 * @param categories 横轴分类（X 轴刻度 / 饼图扇区名 / 漏斗层级）
 * @param series     数据系列；饼图与仪表盘只应有一条
 */
public record ChartSpec(
        String type,
        String title,
        List<String> categories,
        List<Series> series) {

    /**
     * 一条数据系列。
     *
     * @param name   系列名（图例文字）
     * @param values 与 {@code categories} 一一对应的数值
     */
    public record Series(String name, List<BigDecimal> values) {
    }

}
