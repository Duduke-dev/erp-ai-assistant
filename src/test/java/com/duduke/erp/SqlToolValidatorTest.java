package com.duduke.erp;

import com.duduke.erp.entity.po.LlmTool;
import com.duduke.erp.service.tool.ToolNames;
import com.duduke.erp.service.tool.dynamic.SqlTemplateBinder;
import com.duduke.erp.service.tool.dynamic.SqlToolValidator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import tools.jackson.databind.ObjectMapper;

/**
 * 动态 SQL Tool 安全校验器的测试。
 * <p>
 * 它守的是「模型生成的 SQL 直连数据库」这个最危险的入口，规则一条都不能松。
 * 特别注意 {@link #rejectsForbiddenKeywordEvenInsideCommentOrString}：
 * 校验器<b>故意不区分注释与字符串</b>，宁可误伤也绝不放过写操作。
 */
class SqlToolValidatorTest {

    private final SqlToolValidator validator =
            new SqlToolValidator(new ObjectMapper(), new SqlTemplateBinder(new ObjectMapper()));

    @Test
    @DisplayName("合法配置通过")
    void acceptsValidTool() {
        assertThatCode(() -> this.validator.validateTool(validTool())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Tool 名称：必须以字母或下划线开头，不能含连字符")
    void validatesToolNameFormat() {
        assertThatThrownBy(() -> this.validator.validateTool(tool("1abc", sql())))
                .hasMessageContaining("Tool 名称");
        assertThatThrownBy(() -> this.validator.validateTool(tool("my-tool", sql())))
                .hasMessageContaining("Tool 名称");
        assertThatThrownBy(() -> this.validator.validateTool(tool("has space", sql())))
                .hasMessageContaining("Tool 名称");
    }

    // 注：「动态 Tool 不得占用系统保留名」这条用例已移除——原先唯一的保留名属于图表方案 Tool，
    // 图表功能废弃后该名称也一并删除，ToolNames.RESERVED 当前为空集，没有可构造的用例。
    // 将来新增系统内部 Tool 时应补回此断言，否则保留名校验会变成无人覆盖的分支。

    @Test
    @DisplayName("描述不能为空")
    void rejectsBlankDescription() {
        LlmTool tool = validTool();
        tool.setToolDesc("  ");
        assertThatThrownBy(() -> this.validator.validateTool(tool))
                .hasMessageContaining("描述不能为空");
    }

    @Test
    @DisplayName("SQL 不能为空、不能含分号（多语句是注入的经典入口）")
    void rejectsBlankOrMultiStatementSql() {
        assertThatThrownBy(() -> this.validator.validateSql("   "))
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> this.validator.validateSql("SELECT 1; DROP TABLE product"))
                .hasMessageContaining("分号");
    }

    @Test
    @DisplayName("SQL 必须以 SELECT 开头")
    void rejectsNonSelectEntry() {
        assertThatThrownBy(() -> this.validator.validateSql("UPDATE product SET a = 1"))
                .hasMessageContaining("SELECT");
        assertThatThrownBy(() -> this.validator.validateSql("EXPLAIN SELECT 1"))
                .hasMessageContaining("SELECT");
    }

    @Test
    @DisplayName("写操作与 DDL 关键字一律拒绝")
    void rejectsForbiddenKeywords() {
        // 都以 SELECT 开头绕过入口检查，但含被禁词
        for (String keyword : new String[] {"insert", "update", "delete", "drop", "alter",
                "truncate", "create", "replace", "merge", "call", "grant", "revoke", "into"}) {
            String sql = "SELECT * FROM product WHERE x = '" + keyword + "'";
            assertThatThrownBy(() -> this.validator.validateSql(sql))
                    .as("关键字 %s 应被拒绝", keyword)
                    .hasMessageContaining("禁止");
        }
    }

    @Test
    @DisplayName("SELECT ... INTO 必须被拒绝——它会建表，却能绕过参考实现的全套校验")
    void rejectsSelectInto() {
        // 参考实现的黑名单没有 into，这句在它那里是完全合法的：
        // SELECT 开头 ✓、无被禁词 ✓、不是子查询 ✓、无 union ✓ —— 但它会建表。
        assertThatThrownBy(() -> this.validator.validateSql(
                "SELECT code INTO stolen_table FROM product"))
                .as("SELECT ... INTO 会创建表，属于写操作")
                .hasMessageContaining("禁止");
    }

    @Test
    @DisplayName("关键词检测不区分注释与字符串（故意 fail-closed）")
    void rejectsForbiddenKeywordEvenInsideCommentOrString() {
        // 误伤一条合法配置的代价，远低于放过一条写操作
        assertThatThrownBy(() -> this.validator.validateSql("SELECT * FROM product -- drop"))
                .hasMessageContaining("禁止");
        assertThatThrownBy(() -> this.validator.validateSql(
                "SELECT * FROM product WHERE remark = 'please delete this'"))
                .hasMessageContaining("禁止");
    }

    @Test
    @DisplayName("只放行单层 SELECT：子查询 / union / intersect / except 一律拒绝")
    void rejectsMultiQueryBlocks() {
        // 租户注入只处理第一个 WHERE，这些形态会让注入落错位置从而绕过隔离
        assertThatThrownBy(() -> this.validator.validateSql(
                "SELECT * FROM product WHERE id IN (SELECT id FROM inventory)"))
                .hasMessageContaining("单层 SELECT");
        assertThatThrownBy(() -> this.validator.validateSql(
                "SELECT a FROM t1 UNION SELECT a FROM t2"))
                .hasMessageContaining("单层 SELECT");
        assertThatThrownBy(() -> this.validator.validateSql(
                "SELECT a FROM t1 INTERSECT SELECT a FROM t2"))
                .hasMessageContaining("单层 SELECT");
        assertThatThrownBy(() -> this.validator.validateSql(
                "SELECT a FROM t1 EXCEPT SELECT a FROM t2"))
                .hasMessageContaining("单层 SELECT");
    }

    @Test
    @DisplayName("CTE 在入口就被拒绝（不以 SELECT 开头）")
    void rejectsCommonTableExpression() {
        // 参考实现先允许 with 开头、又用另一条规则禁掉，语义绕。
        // 本实现入口直接要求 select，错误信息更明确。
        assertThatThrownBy(() -> this.validator.validateSql(
                "WITH t AS (SELECT * FROM product) SELECT * FROM t"))
                .hasMessageContaining("SELECT");
    }

    @Test
    @DisplayName("返回行数上限只允许 1 ~ 500")
    void validatesResultLimit() {
        assertThatThrownBy(() -> this.validator.validateResultLimit(0))
                .hasMessageContaining("1 到 500");
        assertThatThrownBy(() -> this.validator.validateResultLimit(501))
                .hasMessageContaining("1 到 500");
        assertThatCode(() -> this.validator.validateResultLimit(1)).doesNotThrowAnyException();
        assertThatCode(() -> this.validator.validateResultLimit(500)).doesNotThrowAnyException();
        // 为空表示用默认值
        assertThatCode(() -> this.validator.validateResultLimit(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("主表别名必须是安全标识符")
    void validatesTableAlias() {
        assertThatThrownBy(() -> this.validator.validateTableAlias("o; DROP TABLE product"))
                .hasMessageContaining("主表别名");
        assertThatThrownBy(() -> this.validator.validateTableAlias("1o"))
                .hasMessageContaining("主表别名");
        assertThatCode(() -> this.validator.validateTableAlias("o")).doesNotThrowAnyException();
        assertThatCode(() -> this.validator.validateTableAlias(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("入参 Schema 必须是合法 JSON 对象")
    void validatesInputSchema() {
        assertThatThrownBy(() -> this.validator.validateInputSchema(null))
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> this.validator.validateInputSchema("[1,2]"))
                .hasMessageContaining("JSON 对象");
        assertThatThrownBy(() -> this.validator.validateInputSchema("{not json"))
                .hasMessageContaining("不是合法 JSON");
    }

    @Test
    @DisplayName("配置期交叉校验：模板引用的参数必须在 Schema 里声明过")
    void rejectsTemplateParameterNotDeclaredInSchema() {
        // 这类错误若不在此拦下，就是一颗哑弹——要等模型第一次调用才炸
        assertThatThrownBy(() -> this.validator.validateTemplateParameters(
                "SELECT * FROM product WHERE custmer = :custmer",
                "{\"properties\":{\"customer\":{\"type\":\"string\"}}}"))
                .hasMessageContaining("custmer");
    }

    private LlmTool validTool() {
        return tool("query_product_by_code", sql());
    }

    private String sql() {
        return "SELECT code, name FROM product WHERE code = :code";
    }

    private LlmTool tool(String name, String sqlTemplate) {
        LlmTool tool = new LlmTool();
        tool.setToolName(name);
        tool.setToolDesc("按编码查询产品");
        tool.setInputSchema("""
                {"type":"object","properties":{"code":{"type":"string"}},"required":["code"]}
                """);
        tool.setSqlTemplate(sqlTemplate);
        tool.setResultLimit(50);
        tool.setStatus("active");
        return tool;
    }

}
