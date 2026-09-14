package com.duduke.erp;

import java.util.Map;

import com.duduke.erp.service.tool.dynamic.BoundSql;
import com.duduke.erp.service.tool.dynamic.SqlTemplateBinder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import tools.jackson.databind.ObjectMapper;

/**
 * SQL 模板命名参数绑定器的测试。
 * <p>
 * 核心不变量：<b>实参永远只走 JDBC 绑定，绝不拼进 SQL 文本</b>。
 * 因此即使模型传来 {@code ' OR 1=1 --} 这样的值，它也只是一个普通字符串参数。
 */
class SqlTemplateBinderTest {

    private static final String SCHEMA_CODE = """
            {"type":"object","properties":{"code":{"type":"string"}},"required":["code"]}
            """;

    private static final String SCHEMA_TWO = """
            {"type":"object","properties":{"code":{"type":"string"},"limit":{"type":"integer"}}}
            """;

    private final SqlTemplateBinder binder = new SqlTemplateBinder(new ObjectMapper());

    @Test
    @DisplayName("命名参数按出现顺序转成 ? 并收集实参")
    void bindsNamedParametersInOrder() {
        BoundSql bound = this.binder.bind(
                "SELECT * FROM product WHERE code = :code",
                SCHEMA_CODE,
                "{\"code\":\"P-1001\"}");

        assertThat(bound.sql()).isEqualTo("SELECT * FROM product WHERE code = ?");
        assertThat(bound.arguments()).containsExactly("P-1001");
    }

    @Test
    @DisplayName("多个参数按 SQL 中出现顺序排列，与 JSON 字段顺序无关")
    void keepsSqlOrderNotJsonOrder() {
        // JSON 里 code 在前、limit 在后；SQL 里 limit 先出现——实参必须按 SQL 顺序
        BoundSql bound = this.binder.bind(
                "SELECT * FROM product WHERE limit_col = :limit AND code = :code",
                SCHEMA_TWO,
                "{\"code\":\"P-1001\",\"limit\":5}");

        assertThat(bound.sql())
                .isEqualTo("SELECT * FROM product WHERE limit_col = ? AND code = ?");
        assertThat(bound.arguments()).containsExactly(5L, "P-1001");
    }

    @Test
    @DisplayName("PostgreSQL 的 :: 类型转换符不被误当作命名参数")
    void skipsPostgresCastOperator() {
        // 若不跳过 ::，这里会把 :numeric 当成参数名，报出莫名其妙的「未声明参数 numeric」
        BoundSql bound = this.binder.bind(
                "SELECT * FROM product WHERE weight = :weight::numeric",
                """
                        {"type":"object","properties":{"weight":{"type":"string"}}}
                        """,
                "{\"weight\":\"12\"}");

        assertThat(bound.sql()).isEqualTo("SELECT * FROM product WHERE weight = ?::numeric");
        assertThat(bound.arguments()).containsExactly("12");
    }

    @Test
    @DisplayName("恶意值只作为参数，不会改变 SQL 结构")
    void maliciousValueStaysAParameter() {
        BoundSql bound = this.binder.bind(
                "SELECT * FROM product WHERE code = :code",
                SCHEMA_CODE,
                "{\"code\":\"' OR 1=1 --\"}");

        // SQL 里没有出现注入的内容，它只存在于实参列表
        assertThat(bound.sql()).isEqualTo("SELECT * FROM product WHERE code = ?");
        assertThat(bound.arguments()).containsExactly("' OR 1=1 --");
    }

    @Test
    @DisplayName("模板引用了未在 Schema 声明的参数时拒绝")
    void rejectsUndeclaredParameter() {
        assertThatThrownBy(() -> this.binder.bind(
                "SELECT * FROM product WHERE secret = :unknown",
                SCHEMA_CODE,
                "{\"unknown\":\"x\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未声明的参数")
                .hasMessageContaining("unknown");
    }

    @Test
    @DisplayName("模型漏传必需参数时拒绝，并指出缺哪个")
    void rejectsMissingArgument() {
        assertThatThrownBy(() -> this.binder.bind(
                "SELECT * FROM product WHERE code = :code",
                SCHEMA_CODE,
                "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少必需参数")
                .hasMessageContaining("code");
    }

    @Test
    @DisplayName("模板无参数时，空输入是合法的")
    void allowsEmptyInputWhenNoParameters() {
        BoundSql bound = this.binder.bind(
                "SELECT COUNT(*) AS total FROM product",
                "{\"type\":\"object\",\"properties\":{}}",
                null);

        assertThat(bound.sql()).isEqualTo("SELECT COUNT(*) AS total FROM product");
        assertThat(bound.arguments()).isEmpty();
    }

    @Test
    @DisplayName("入参不是 JSON 对象时拒绝")
    void rejectsNonObjectInput() {
        assertThatThrownBy(() -> this.binder.bind(
                "SELECT 1", "{\"type\":\"object\"}", "[1,2,3]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须是 JSON 对象");
    }

    @Test
    @DisplayName("数组/对象类型的参数值拒绝——JDBC 绑不了，且没有合理查询语义")
    void rejectsStructuredArgumentValues() {
        assertThatThrownBy(() -> this.binder.bind(
                "SELECT * FROM product WHERE code = :code",
                SCHEMA_CODE,
                "{\"code\":{\"nested\":1}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只支持字符串、数字、布尔");
    }

    @Test
    @DisplayName("Schema 不是合法 JSON 时拒绝")
    void rejectsMalformedSchema() {
        assertThatThrownBy(() -> this.binder.bind("SELECT 1", "{not json", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是合法 JSON");
    }

    @Test
    @DisplayName("提取 Schema 声明的参数名")
    void extractsDeclaredParameters() {
        assertThat(this.binder.extractDeclaredParameters(SCHEMA_TWO))
                .containsExactly("code", "limit");
    }

    @Test
    @DisplayName("提取模板中的占位符名（供配置期交叉校验）")
    void extractsPlaceholders() {
        assertThat(this.binder.extractPlaceholders(
                "SELECT * FROM t WHERE a = :alpha AND b = :beta AND a2 = :alpha"))
                .containsExactly("alpha", "beta");
    }

    @Test
    @DisplayName("解析参数 JSON 为普通 Java 值")
    void parsesArgumentsToPlainValues() {
        Map<String, Object> parsed = this.binder.parseArguments(
                "{\"s\":\"文本\",\"i\":3,\"d\":1.5,\"b\":true,\"n\":null}");

        assertThat(parsed).containsEntry("s", "文本")
                .containsEntry("i", 3L)
                .containsEntry("d", 1.5)
                .containsEntry("b", true)
                .containsEntry("n", null);
    }

}
