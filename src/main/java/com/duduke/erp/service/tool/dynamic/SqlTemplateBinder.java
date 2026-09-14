package com.duduke.erp.service.tool.dynamic;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 把 SQL 模板里的 {@code :name} 命名参数绑定成 JDBC {@code ?}。
 * <p>
 * <b>实参只走 JDBC 绑定，绝不拼进 SQL 文本</b>——这是动态 Tool 防注入的根本。
 * 模型给的值哪怕写着 {@code ' OR 1=1 --}，也只是被当作一个普通字符串参数。
 * <p>
 * 绑定前做两道校验（缺一不可）：
 * <ol>
 *   <li>占位符必须在入参 Schema 的 {@code properties} 里声明过——
 *       挡住模板里写了个 schema 没暴露的参数；</li>
 *   <li>模型必须真的传了这个参数——挡住模板声明了但模型漏传。</li>
 * </ol>
 * 两者任一不满足都抛可读错误，让模型能改正重试。
 */
@Component
@RequiredArgsConstructor
public class SqlTemplateBinder {

    /**
     * SQL 模板中的命名参数。
     * <p>
     * {@code (?<!:)} 这个反向断言<b>必须保留</b>：PostgreSQL 的 {@code ::} 是类型转换符
     * （如 {@code :amount::numeric}），不加断言会把 {@code ::numeric} 里的
     * {@code :numeric} 也当成参数，报出莫名其妙的「未声明参数 numeric」。
     */
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("(?<!:):([A-Za-z_][A-Za-z0-9_]*)");

    private final ObjectMapper objectMapper;

    /**
     * 绑定命名参数。
     *
     * @param sqlTemplate SQL 模板
     * @param inputSchema 入参 JSON Schema
     * @param toolInput   模型传入的参数 JSON
     * @return 替换后的 SQL 与按序排列的实参
     */
    public BoundSql bind(String sqlTemplate, String inputSchema, String toolInput) {
        Set<String> declared = extractDeclaredParameters(inputSchema);
        Map<String, Object> arguments = parseArguments(toolInput);

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(sqlTemplate);
        StringBuilder sql = new StringBuilder();
        java.util.List<Object> values = new java.util.ArrayList<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!declared.contains(name)) {
                throw new IllegalArgumentException("SQL 模板引用了未声明的参数：" + name);
            }
            if (!arguments.containsKey(name)) {
                throw new IllegalArgumentException("缺少必需参数：" + name);
            }
            matcher.appendReplacement(sql, Matcher.quoteReplacement("?"));
            values.add(arguments.get(name));
        }
        matcher.appendTail(sql);
        return new BoundSql(sql.toString().trim(), values);
    }

    /**
     * 取 Schema 声明的入参名集合。
     *
     * @throws IllegalArgumentException Schema 不是合法 JSON 或不是对象
     */
    public Set<String> extractDeclaredParameters(String inputSchema) {
        JsonNode root = readTree(inputSchema, "Tool 入参 Schema");
        JsonNode properties = root.get("properties");
        Set<String> names = new LinkedHashSet<>();
        if (properties != null && properties.isObject()) {
            names.addAll(properties.propertyNames());
        }
        return names;
    }

    /**
     * 解析模型传入的参数 JSON。
     * <p>
     * 空输入按「无参数」处理而不是报错：模板可能确实不需要参数
     * （例如「列出所有产品」）。
     */
    public Map<String, Object> parseArguments(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return new LinkedHashMap<>();
        }
        JsonNode root = readTree(toolInput, "Tool 参数");
        if (!root.isObject()) {
            throw new IllegalArgumentException("Tool 参数必须是 JSON 对象");
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        root.properties().forEach(entry ->
                arguments.put(entry.getKey(), toPlainValue(entry.getValue())));
        return arguments;
    }

    /**
     * 取 SQL 模板里出现的全部命名参数。
     * <p>
     * 单独暴露出来供 {@link SqlToolValidator} 在<b>配置时</b>做交叉校验：
     * 模板引用的参数必须都在 Schema 里声明过。
     * 正则只此一份——安全相关的匹配规则复制两份迟早会漂移。
     */
    public Set<String> extractPlaceholders(String sqlTemplate) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(sqlTemplate == null ? "" : sqlTemplate);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /** 把 JsonNode 还原成 JDBC 能绑定的普通 Java 值 */
    private Object toPlainValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asString();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        // 数组/对象作为查询参数没有合理语义，交给 JDBC 绑定必然出错，提前拒绝
        throw new IllegalArgumentException("Tool 参数只支持字符串、数字、布尔与 null");
    }

    private JsonNode readTree(String json, String what) {
        try {
            return this.objectMapper.readTree(json);
        }
        catch (RuntimeException e) {
            throw new IllegalArgumentException(what + " 不是合法 JSON", e);
        }
    }

}
