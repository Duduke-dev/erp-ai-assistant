package com.duduke.erp.service.tool;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Tool 入参的规范化处理。
 * <p>
 * <b>{@link #like} 存在的理由：PG 与 MySQL 的 {@code CONCAT} 遇 NULL 语义相反。</b>
 * 参考实现写的是 {@code LIKE CONCAT('%', ?, '%')}——MySQL 下传 NULL 得 NULL（不匹配任何行），
 * PG 下 {@code concat('%', NULL, '%')} 得 {@code '%%'} <b>匹配全部行</b>。
 * 同一句 SQL 换库后会从「查不到」变成「把所有行都吐给模型」，且不报错。
 * 所以 {@code %} 一律在 Java 侧拼好再绑定。
 * <p>
 * <b>{@link #parseDate} 抛的都是可读错误</b>：模型传错日期格式是常事，
 * 报错信息会作为 Tool 结果回给模型，它能据此改正重试；
 * 抛一个 {@code DateTimeParseException} 的原始堆栈则毫无指导价值。
 */
public final class ToolParams {

    /**
     * 把原始关键字包成 LIKE 模式。
     * <p>
     * 传入空值时返回 {@code "%"}（即不过滤），语义是「列出全部」——
     * 这比返回「匹配不到」更符合用户问「有哪些订单」时的预期。
     */
    public static String like(String raw) {
        return "%" + (raw == null ? "" : raw.trim()) + "%";
    }

    /**
     * 解析 {@code yyyy-MM-dd} 日期。
     *
     * @param field 字段中文名，用于拼出可读的报错
     */
    public static LocalDate parseDate(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空，格式为 yyyy-MM-dd");
        }
        try {
            return LocalDate.parse(raw.trim());
        }
        catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    field + "格式应为 yyyy-MM-dd，实际传入：" + raw);
        }
    }

    private ToolParams() {
    }

}
