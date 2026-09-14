package com.duduke.erp;

import com.duduke.erp.service.tool.dynamic.TenantSqlInjection;
import com.duduke.erp.service.tool.dynamic.TenantSqlInjector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户条件注入器的测试。
 * <p>
 * <b>本类里最重要的一条是「防 OR 优先级绕过」。</b>
 * 若只把 {@code AND ent_code = ?} 追加到 WHERE 条件后面，
 * 模板里只要有 {@code OR}，租户条件就会被 {@code AND} 的更高优先级"吃掉"，
 * 变成跨租户查询——不报错、界面正常，只是看到了别人的数据。
 * <p>
 * 纯单元测试，不需要 Spring 上下文。
 */
class TenantSqlInjectorTest {

    private final TenantSqlInjector injector = new TenantSqlInjector();

    @Test
    @DisplayName("防 OR 优先级绕过：原 WHERE 条件必须整体加括号")
    void wrapsOriginalWhereConditionInParentheses() {
        String sql = "SELECT * FROM sales_order WHERE customer_name = ? OR status = 'draft'";

        String injected = this.injector.inject(sql, null);

        // 关键断言：括号必须存在。缺了它 SQL 会变成
        // ... WHERE customer_name = ? OR status = 'draft' AND ent_code = ?
        // 因 AND 优先级更高，等价于 customer_name = ? OR (status='draft' AND ent_code=?)
        // → 前半支完全不受租户限制。
        assertThat(injected).contains("WHERE (customer_name = ? OR status = 'draft')");
        assertThat(injected).endsWith("AND ent_code = ?");
    }

    @Test
    @DisplayName("防 OR 绕过的对照：加了括号后，OR 的两支都在租户约束内")
    void parenthesesCoverWholeCondition() {
        String injected = this.injector.inject(
                "SELECT * FROM t WHERE a = ? OR b = ?", null);

        // 括号把两个 OR 分支一起包住，租户条件作用于整体
        assertThat(injected).isEqualTo(
                "SELECT * FROM t WHERE (a = ? OR b = ?) AND ent_code = ?");
    }

    @Test
    @DisplayName("无 WHERE 子句时补一个，而不是追加到末尾")
    void addsWhereClauseWhenMissing() {
        String injected = this.injector.inject("SELECT * FROM product", null);

        assertThat(injected).isEqualTo("SELECT * FROM product WHERE ent_code = ?");
    }

    @Test
    @DisplayName("租户条件插在 ORDER BY / GROUP BY / LIMIT 之前")
    void insertsBeforeTailClauses() {
        assertThat(this.injector.inject("SELECT * FROM t WHERE a = ? ORDER BY b", null))
                .isEqualTo("SELECT * FROM t WHERE (a = ?) AND ent_code = ? ORDER BY b");

        assertThat(this.injector.inject("SELECT a, COUNT(*) FROM t WHERE a = ? GROUP BY a", null))
                .isEqualTo("SELECT a, COUNT(*) FROM t WHERE (a = ?) AND ent_code = ? GROUP BY a");

        assertThat(this.injector.inject("SELECT * FROM t WHERE a = ? LIMIT 10", null))
                .isEqualTo("SELECT * FROM t WHERE (a = ?) AND ent_code = ? LIMIT 10");
    }

    @Test
    @DisplayName("取最早的尾部子句：ORDER BY 与 LIMIT 同时存在时插在 ORDER BY 前")
    void usesEarliestTailClause() {
        String injected = this.injector.inject(
                "SELECT * FROM t WHERE a = ? ORDER BY b LIMIT 10", null);

        assertThat(injected).isEqualTo(
                "SELECT * FROM t WHERE (a = ?) AND ent_code = ? ORDER BY b LIMIT 10");
    }

    @Test
    @DisplayName("传别名时用 alias.ent_code，避免 JOIN 时列名歧义")
    void usesTableAliasWhenProvided() {
        String injected = this.injector.inject(
                "SELECT o.id FROM sales_order o JOIN sales_order_item d ON d.order_no = o.order_no",
                "o");

        assertThat(injected).endsWith("WHERE o.ent_code = ?");
    }

    @Test
    @DisplayName("多行 SQL 先压缩空白再处理")
    void normalizesWhitespace() {
        String injected = this.injector.inject("""
                SELECT *
                FROM product
                WHERE   a = ?
                """, null);

        assertThat(injected).isEqualTo("SELECT * FROM product WHERE (a = ?) AND ent_code = ?");
    }

    @Test
    @DisplayName("租户参数下标 = 插入点之前的 ? 个数（不能直接追加到末尾）")
    void computesTenantParameterIndex() {
        // 原 SQL 有 2 个 ?，都在插入点之前 → 租户实参应插在下标 2
        TenantSqlInjection injection = this.injector.injectWithParameterIndex(
                "SELECT * FROM t WHERE a = ? AND b = ? ORDER BY c", null);

        assertThat(injection.tenantParameterIndex()).isEqualTo(2);
        assertThat(injection.sql())
                .isEqualTo("SELECT * FROM t WHERE (a = ? AND b = ?) AND ent_code = ? ORDER BY c");
    }

    @Test
    @DisplayName("统计 ? 时忽略字符串字面量里的问号")
    void ignoresQuestionMarksInsideStringLiterals() {
        // '%?%' 里的 ? 不是占位符，不能计入——否则租户实参下标会偏大导致参数错位
        TenantSqlInjection injection = this.injector.injectWithParameterIndex(
                "SELECT * FROM t WHERE remark LIKE '%?%' AND a = ?", null);

        assertThat(injection.tenantParameterIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("字符串字面量里的转义单引号不破坏引号配对")
    void handlesEscapedSingleQuote() {
        // 'it''s a ? test' 里的 ? 也在字面量内；'' 是转义单引号
        TenantSqlInjection injection = this.injector.injectWithParameterIndex(
                "SELECT * FROM t WHERE remark = 'it''s a ? test' AND a = ? AND b = ?", null);

        assertThat(injection.tenantParameterIndex()).isEqualTo(2);
    }

    @Test
    @DisplayName("无 WHERE 的聚合查询：租户条件加在聚合之前，结果被正确限定")
    void injectsIntoAggregateWithoutWhere() {
        TenantSqlInjection injection = this.injector.injectWithParameterIndex(
                "SELECT COUNT(*) FROM sales_order", null);

        assertThat(injection.sql()).isEqualTo("SELECT COUNT(*) FROM sales_order WHERE ent_code = ?");
        assertThat(injection.tenantParameterIndex()).isZero();
    }

}
