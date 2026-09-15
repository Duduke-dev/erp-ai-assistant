package com.duduke.erp.service.chart;

/**
 * 图表方案：<b>模型唯一需要输出的东西</b>。
 * <p>
 * 刻意只留 type + title 两个字段：
 * 字段绑定、聚合方式、坐标轴、图例、颜色等选项<b>全部由后端生成</b>，
 * 不让模型接触数据 schema。原因有三：
 * <ol>
 *   <li>模型不知道 Tool 返回的列名与类型，让它选字段等于让它猜；</li>
 *   <li>把 schema 暴露给模型会显著增加 prompt 与被注入的风险面；</li>
 *   <li>前端只接收纯数据协议、不执行任何函数，避免 XSS。</li>
 * </ol>
 * 对应的 Tool 入参 schema 设 {@code additionalProperties:false}，
 * 只允许这两个字段，type 用枚举约束（见 {@code ChartPlanToolCallback}）。
 */
public record ChartPlan(ChartType type, String title) {
}
