package com.duduke.erp;

import java.util.UUID;

import com.duduke.erp.entity.po.LlmTool;
import com.duduke.erp.service.tool.dynamic.DatabaseToolExecutor;
import com.duduke.erp.service.tool.dynamic.ToolQueryResult;
import com.duduke.erp.tenant.TenantContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 动态 SQL Tool 执行器的端到端验证。
 * <p>
 * 这里验证的是整个 M3.3 最核心的两条安全承诺：
 * <ol>
 *   <li><b>防 OR 优先级绕过</b>——用真实的双租户数据跑一遍，
 *       若注入时漏了那对括号，就会查出别的租户的数据；</li>
 *   <li><b>只读事务真的生效</b>——校验器是关键词语黑名单，必然枚举不全；
 *       只读事务是数据库层的兜底。</li>
 * </ol>
 * 造数用 JdbcTemplate 直连（需要往多个租户写，走 Mapper 会被强制落到当前租户）。
 */
@SpringBootTest
class DatabaseToolExecutorTest {

    private static final String TENANT = "DEMO";

    private static final String OTHER_TENANT = "OTHER";

    @Autowired
    private DatabaseToolExecutor executor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String tag;

    @BeforeEach
    void setUp() {
        this.tag = "DTE" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TenantContext.set(TENANT, 1L);
    }

    @AfterEach
    void tearDown() {
        this.jdbcTemplate.update("DELETE FROM product WHERE product_code LIKE ?", this.tag + "%");
        TenantContext.clear();
    }

    @Test
    @DisplayName("防 OR 优先级绕过（端到端）：两个租户都有数据时只返回本租户")
    void orConditionDoesNotLeakAcrossTenants() {
        seedProduct(TENANT, this.tag + "-D1");
        seedProduct(OTHER_TENANT, this.tag + "-O1");

        // 模板里的 OR 是触发点：若注入时没把原条件整体加括号，SQL 会变成
        //   WHERE product_code LIKE ? OR (product_name LIKE ? AND ent_code = 'DEMO')
        // 前半支不受租户约束 → 把 OTHER 的产品也查出来。
        LlmTool tool = tool("dyn_or_probe",
                "SELECT product_code FROM product "
                        + "WHERE product_code LIKE :pattern OR product_name LIKE :pattern");

        ToolQueryResult result = this.executor.execute(tool,
                "{\"pattern\":\"" + this.tag + "%\"}");

        assertThat(result.rows())
                .as("只应看到本租户的产品；出现 -O1 就是跨租户泄漏")
                .isNotEmpty()
                .allSatisfy(row -> assertThat(String.valueOf(row.get("product_code")))
                        .contains("-D1"));
    }

    @Test
    @DisplayName("无 WHERE 的模板也能正确加租户条件")
    void injectsTenantIntoQueryWithoutWhere() {
        seedProduct(TENANT, this.tag + "-D2");
        seedProduct(OTHER_TENANT, this.tag + "-O2");

        LlmTool tool = tool("dyn_no_where", "SELECT product_code FROM product");

        ToolQueryResult result = this.executor.execute(tool, null);

        assertThat(result.rows())
                .isNotEmpty()
                .allSatisfy(row -> assertThat(String.valueOf(row.get("product_code")))
                        .contains("-D2"));
    }

    @Test
    @DisplayName("命名参数正确绑定，结果按参数过滤")
    void bindsNamedParameter() {
        seedProduct(TENANT, this.tag + "-D3");
        seedProduct(TENANT, this.tag + "-D4");

        LlmTool tool = tool("dyn_by_code",
                "SELECT product_code FROM product WHERE product_code = :code");

        ToolQueryResult result = this.executor.execute(tool,
                "{\"code\":\"" + this.tag + "-D4\"}");

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0)).containsEntry("product_code", this.tag + "-D4");
    }

    @Test
    @DisplayName("结果行数上限生效")
    void appliesResultLimit() {
        seedProduct(TENANT, this.tag + "-L1");
        seedProduct(TENANT, this.tag + "-L2");
        seedProduct(TENANT, this.tag + "-L3");

        LlmTool tool = tool("dyn_limited",
                "SELECT product_code FROM product WHERE product_code LIKE :pattern");
        tool.setResultLimit(2);

        ToolQueryResult result = this.executor.execute(tool,
                "{\"pattern\":\"" + this.tag + "-L%\"}");

        assertThat(result.rows()).hasSize(2);
    }

    @Test
    @DisplayName("模板自带 LIMIT 时不产生双重 LIMIT（外包派生表）")
    void wrapsExistingLimitInDerivedTable() {
        seedProduct(TENANT, this.tag + "-W1");

        LlmTool tool = tool("dyn_with_limit",
                "SELECT product_code FROM product WHERE product_code LIKE :pattern LIMIT 10");
        tool.setResultLimit(5);

        // 若直接追加就成了 "... LIMIT 10 LIMIT 5" 语法错误
        ToolQueryResult result = this.executor.execute(tool,
                "{\"pattern\":\"" + this.tag + "-W%\"}");

        assertThat(result.rows()).hasSize(1);
    }

    @Test
    @DisplayName("动态 Tool 在只读事务中执行（直接读 transaction_read_only 验证）")
    void executesInReadOnlyTransaction() {
        seedProduct(TENANT, this.tag + "-RO1");

        // 直接让数据库把事务的只读标志报回来。比"构造一条会被拒绝的写语句"可靠：
        // 写语句既要能通过校验器、又要真的被 PG 在只读下拒绝，两个条件很难同时满足
        // （例如 nextval 就被 PG 允许在只读事务里执行）。
        LlmTool tool = tool("dyn_readonly_probe",
                "SELECT current_setting('transaction_read_only') AS ro FROM product");

        ToolQueryResult result = this.executor.execute(tool, null);

        assertThat(result.rows()).isNotEmpty();
        assertThat(String.valueOf(result.rows().get(0).get("ro")))
                .as("事务必须处于只读状态，否则校验器漏掉的写操作会直接落库")
                .isEqualTo("on");
    }

    @Test
    @DisplayName("拿不到租户上下文时直接失败，绝不放行无租户条件的查询")
    void failsLoudlyWithoutTenantContext() {
        TenantContext.clear();
        LlmTool tool = tool("dyn_no_tenant", "SELECT product_code FROM product");

        assertThatThrownBy(() -> this.executor.execute(tool, null))
                .as("宁可不查，也不能查了全部租户")
                .hasMessageContaining("租户");
    }

    private void seedProduct(String entCode, String productCode) {
        this.jdbcTemplate.update("""
                INSERT INTO product (ent_code, product_code, product_name, unit)
                VALUES (?, ?, ?, '个')
                """, entCode, productCode, productCode + " 名称");
    }

    private LlmTool tool(String name, String sqlTemplate) {
        LlmTool tool = new LlmTool();
        tool.setToolName(name);
        tool.setToolDesc("测试用动态 Tool");
        tool.setInputSchema("""
                {"type":"object","properties":{"code":{"type":"string"},"pattern":{"type":"string"}}}
                """);
        tool.setSqlTemplate(sqlTemplate);
        tool.setResultLimit(50);
        tool.setStatus("active");
        return tool;
    }

}
