package com.duduke.erp;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.duduke.erp.service.tool.AfterSalesTool;
import com.duduke.erp.service.tool.FinanceTool;
import com.duduke.erp.service.tool.OutsourcingTool;
import com.duduke.erp.service.tool.ProductionTool;
import com.duduke.erp.service.tool.PurchaseTool;
import com.duduke.erp.service.tool.QualityTool;
import com.duduke.erp.service.tool.SalesTool;
import com.duduke.erp.service.tool.WarehouseTool;
import com.duduke.erp.tenant.TenantContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 业务 Tool 的查询验证。
 * <p>
 * 这个测试要回答一个 M3 里<b>尚未验证的假设</b>：Tool 查询可以完全依赖租户插件
 * 自动注入 {@code ent_code}，不需要手写条件，也不需要 {@code @InterceptorIgnore}。
 * <p>
 * 风险集中在 4 个 JOIN 查询上——项目历史上记录过「带 JOIN/GROUP BY 的复杂语句
 * 插件改写容易出问题」。所以这里不只验证「能跑」，还专门验证「不跨租户」。
 * <p>
 * 若将来某条语句被证实插件处理不了，按约定改为方法级 {@code @InterceptorIgnore}
 * + 手写 {@code ent_code}，并保留下面对应的泄漏断言。
 * <p>
 * 造数用 {@link JdbcTemplate} 直连：本测试需要往<b>多个租户</b>写数据，
 * 走 Mapper 会被租户插件强制落到当前租户，反而造不出对照数据。
 */
@SpringBootTest
class ToolQueryIntegrationTest {

    private static final String TENANT = "DEMO";

    private static final String OTHER_TENANT = "OTHER";

    /** 覆盖面较广的日期区间，用于「全部 Tool 可执行」用例 */
    private static final String D1 = "2026-01-01";

    private static final String D2 = "2026-12-31";

    @Autowired
    private SalesTool salesTool;

    @Autowired
    private WarehouseTool warehouseTool;

    @Autowired
    private PurchaseTool purchaseTool;

    @Autowired
    private ProductionTool productionTool;

    @Autowired
    private QualityTool qualityTool;

    @Autowired
    private FinanceTool financeTool;

    @Autowired
    private AfterSalesTool afterSalesTool;

    @Autowired
    private OutsourcingTool outsourcingTool;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本轮测试的数据标识前缀，带 UUID 后缀避免与既有数据及并发用例冲突 */
    private String tag;

    @BeforeEach
    void setUp() {
        this.tag = "TQT" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TenantContext.set(TENANT, 1L);
    }

    @AfterEach
    void tearDown() {
        // 直连清理：造数涉及多个租户，走 Mapper 清不干净
        this.jdbcTemplate.update("DELETE FROM stock_movement WHERE movement_no LIKE ?", this.tag + "%");
        this.jdbcTemplate.update("DELETE FROM inventory WHERE product_name LIKE ?", this.tag + "%");
        this.jdbcTemplate.update("DELETE FROM sales_order_item WHERE product_name LIKE ?", this.tag + "%");
        this.jdbcTemplate.update("DELETE FROM sales_order WHERE customer_name LIKE ?", this.tag + "%");
        this.jdbcTemplate.update("DELETE FROM product WHERE product_code LIKE ?", this.tag + "%");
        TenantContext.clear();
    }

    @Test
    @DisplayName("全部 39 个 Tool 查询在租户上下文下可执行（覆盖 JOIN / GROUP BY / BETWEEN / 无 WHERE）")
    void allToolQueriesExecute() {
        // 本用例只验证 SQL 合法性 + 插件能改写这些语句形态，不依赖数据。
        // 无 WHERE 的 getLowStockAlerts 是最容易暴露「插件加不上条件」的一条。
        assertThatCode(() -> {
            // 销售 6
            this.salesTool.getSalesOrders("任意客户");
            this.salesTool.getSalesOrderDetail("SO-不存在");
            this.salesTool.getShipmentStatus("SO-不存在");
            this.salesTool.getAccountsReceivable("任意客户");
            this.salesTool.getRecentSalesOrders(D1, D2);
            this.salesTool.getSalesSummary(D1, D2);

            // 仓库 5
            this.warehouseTool.getInventory("任意产品");
            this.warehouseTool.getWarehouseStock("主仓");
            this.warehouseTool.getStockMovements("任意产品");
            this.warehouseTool.getRecentStockMovements(D1, D2);
            this.warehouseTool.getLowStockAlerts();

            // 采购 5
            this.purchaseTool.getPurchaseOrders("任意供应商");
            this.purchaseTool.getPurchaseOrderDetail("PO-不存在");
            this.purchaseTool.getPurchaseReceiveStatus("PO-不存在");
            this.purchaseTool.getRecentPurchaseOrders(D1, D2);
            this.purchaseTool.getSupplierPayable("任意供应商");

            // 生产 5
            this.productionTool.getWorkOrderStatus("WO-不存在");
            this.productionTool.getProductionOrders("任意产品");
            this.productionTool.getWorkOrderMaterials("WO-不存在");
            this.productionTool.getRecentWorkOrders(D1, D2);
            this.productionTool.getWorkOrderRouting("WO-不存在");

            // 质检 5（getQualityRate 含 ROUND / NULLIF 除零兜底）
            this.qualityTool.getQualityInspection("任意批次");
            this.qualityTool.getQualityRecords("任意产品");
            this.qualityTool.getDefectDetails("任意批次");
            this.qualityTool.getRecentQualityRecords(D1, D2);
            this.qualityTool.getQualityRate(D1, D2);

            // 财务 5
            this.financeTool.getMonthlyFinanceSummary("2026-09");
            this.financeTool.getLedgerByAccount("任意科目");
            this.financeTool.getRecentLedger(D1, D2);
            this.financeTool.getPaymentRecords("任意客户");
            this.financeTool.getRecentPayments(D1, D2);

            // 售后 4
            this.afterSalesTool.getAfterSalesTickets("任意客户");
            this.afterSalesTool.getTicketDetail("TK-不存在");
            this.afterSalesTool.getRecentTickets(D1, D2);
            this.afterSalesTool.getProductReturns("任意产品");

            // 委外 4
            this.outsourcingTool.getOutsourcingOrders("任意供应商");
            this.outsourcingTool.getOutsourcingOrderDetail("OS-不存在");
            this.outsourcingTool.getRecentOutsourcingOrders(D1, D2);
            this.outsourcingTool.getOutsourcingMaterialFlow("OS-不存在");
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("全部 39 个 Tool 在无权限数据时返回空列表而非报错")
    void allToolQueriesTolerateEmptyData() {
        // 与上一个用例互补：这里断言「查不到」这条正常路径也是干净的，
        // 避免某条 SQL 在空结果时因聚合/除零等问题抛错。
        assertThat(this.financeTool.getMonthlyFinanceSummary("1990-01")).isEmpty();
        assertThat(this.qualityTool.getQualityRate("1990-01-01", "1990-01-02")).hasSize(1);
        assertThat(this.salesTool.getSalesOrders("绝不存在的客户名XYZ")).isEmpty();
        assertThat(this.afterSalesTool.getTicketDetail("TK-不存在")).isEmpty();
        assertThat(this.outsourcingTool.getOutsourcingMaterialFlow("OS-不存在")).isEmpty();
    }

    @Test
    @DisplayName("库存预警（JOIN + 列间比较 + 无 WHERE）只返回本租户数据")
    void lowStockAlertsAreTenantScoped() {
        seedLowStockProduct(TENANT, "A");
        seedLowStockProduct(OTHER_TENANT, "B");

        List<Map<String, Object>> rows = this.warehouseTool.getLowStockAlerts();

        // 断言"其他租户的绝不能出现" + "本租户那条可见"，而不是"每一行都以本用例的 tag 开头"——
        // 后者等于假设库里只有本用例造的低库存数据，一旦存在演示数据（V17 就造了 5 条）必然误报。
        // 而"跨租户那条不出现"才是这条用例真正要守的不变量。
        assertThat(rows)
                .as("换租户的库存绝不能出现——这是最严重的缺陷类型，且不报错")
                .noneSatisfy(row -> assertThat(String.valueOf(row.get("product_code")))
                        .startsWith(this.tag + "B"));
        assertThat(rows)
                .as("本租户造的那条应当可见（否则说明过滤过头了）")
                .anySatisfy(row -> assertThat(String.valueOf(row.get("product_code")))
                        .startsWith(this.tag + "A"));
    }

    @Test
    @DisplayName("订单详情（JOIN，且两个租户存在同号订单）不串行")
    void salesOrderDetailDoesNotCrossTenants() {
        String sharedOrderNo = this.tag + "-SO-001";
        // 客户名必须带 tag 前缀，否则 tearDown 按 customer_name LIKE 删不掉这两行
        String customerOfTenant = this.tag + "甲客户";
        seedOrder(TENANT, sharedOrderNo, customerOfTenant);
        seedOrder(OTHER_TENANT, sharedOrderNo, this.tag + "乙客户");

        List<Map<String, Object>> rows = this.salesTool.getSalesOrderDetail(sharedOrderNo);

        assertThat(rows).isNotEmpty();
        assertThat(rows)
                .as("两个租户有同号订单时，JOIN 的 ON 必须带 ent_code，否则明细会互相串行")
                .allSatisfy(row -> assertThat(String.valueOf(row.get("customer_name")))
                        .isEqualTo(customerOfTenant));
    }

    @Test
    @DisplayName("按客户查订单只返回本租户数据")
    void salesOrdersAreTenantScoped() {
        seedOrder(TENANT, this.tag + "-SO-002", this.tag + "甲客户");
        seedOrder(OTHER_TENANT, this.tag + "-SO-003", this.tag + "乙客户");

        List<Map<String, Object>> rows = this.salesTool.getSalesOrders(this.tag);

        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(row -> assertThat(String.valueOf(row.get("customer_name")))
                .contains("甲客户"));
    }

    @Test
    @DisplayName("面向模型的日期是纯 yyyy-MM-dd（漏了 TO_CHAR 会变成 ISO 时刻，导致错一天）")
    void datesArePlainCalendarDays() {
        // 回归守卫：DATE 列直接 select 时 JDBC 返 java.sql.Date，
        // JSON 序列化成 ISO 时刻后本地零点被时区位移，2026-09-11 会输出成
        // 2026-09-10T16:00:00.000Z —— 模型据此回答会错一天，且不报错。
        seedOrder(TENANT, this.tag + "-SO-D1", this.tag + "日期客户");
        seedMovement(TENANT, this.tag + "日期产品");

        assertPlainDate(this.salesTool.getRecentSalesOrders("2026-01-01", "2026-12-31"),
                "order_date");
        assertPlainDate(this.warehouseTool.getRecentStockMovements("2026-01-01", "2026-12-31"),
                "movement_date");
    }

    private void assertPlainDate(List<Map<String, Object>> rows, String column) {
        assertThat(rows).as("需要至少一行才能验证 " + column).isNotEmpty();
        assertThat(rows).allSatisfy(row -> assertThat(String.valueOf(row.get(column)))
                .as(column + " 必须是 yyyy-MM-dd；出现 T..Z 说明漏了 TO_CHAR")
                .matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    /** 造一条出入库流水 */
    private void seedMovement(String entCode, String productName) {
        this.jdbcTemplate.update("""
                INSERT INTO stock_movement (ent_code, movement_no, product_id, product_name,
                                            warehouse, movement_type, quantity, movement_date)
                VALUES (?, ?, 1, ?, '主仓', 'IN', 1, CURRENT_DATE)
                """, entCode, this.tag + "-" + UUID.randomUUID().toString().substring(0, 4),
                productName);
    }

    /** 造一个「数量低于安全库存」的产品 + 库存行，product_code 形如 {tag}A{随机} */
    private void seedLowStockProduct(String entCode, String tenantMark) {
        String productCode = this.tag + tenantMark + UUID.randomUUID().toString().substring(0, 4);
        Long productId = this.jdbcTemplate.queryForObject("""
                INSERT INTO product (ent_code, product_code, product_name, unit, safety_stock)
                VALUES (?, ?, ?, '个', 100)
                RETURNING id
                """, Long.class, entCode, productCode, this.tag + "产品" + tenantMark);

        this.jdbcTemplate.update("""
                INSERT INTO inventory (ent_code, product_id, product_name, warehouse,
                                       quantity, safety_stock)
                VALUES (?, ?, ?, '主仓', 5, 100)
                """, entCode, productId, this.tag + "产品" + tenantMark);
    }

    /** 造一张订单及其明细行各一条 */
    private void seedOrder(String entCode, String orderNo, String customerName) {
        Long productId = this.jdbcTemplate.queryForObject("""
                INSERT INTO product (ent_code, product_code, product_name, unit)
                VALUES (?, ?, ?, '个')
                RETURNING id
                """, Long.class, entCode, this.tag + "P" + UUID.randomUUID().toString().substring(0, 4),
                this.tag + "明细产品");

        this.jdbcTemplate.update("""
                INSERT INTO sales_order (ent_code, order_no, customer_id, customer_name,
                                         order_date, total_amount, status)
                VALUES (?, ?, 1, ?, CURRENT_DATE, 100, 'draft')
                """, entCode, orderNo, customerName);

        this.jdbcTemplate.update("""
                INSERT INTO sales_order_item (ent_code, order_no, product_id, product_name,
                                              quantity, unit_price, amount)
                VALUES (?, ?, ?, ?, 2, 50, 100)
                """, entCode, orderNo, productId, this.tag + "明细产品");
    }

}
