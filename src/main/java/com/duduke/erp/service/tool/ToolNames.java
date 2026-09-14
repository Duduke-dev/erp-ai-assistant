package com.duduke.erp.service.tool;

import java.util.Set;

/**
 * 业务 Tool 名称常量。
 * <p>
 * 集中定义而非散落在各 Tool 类里，是为了让注册表能在启动时做
 * 「动态 SQL Tool 不得与代码 Tool 重名」的校验——名字散开就无从校验，
 * 而重名会让模型收到两个同名 function，行为不可预期。
 * <p>
 * 名称沿用参考实现，使两者的能力对照关系可直接映射。
 */
public final class ToolNames {

    // ===== 销售 =====
    public static final String GET_SALES_ORDERS = "getSalesOrders";

    public static final String GET_SALES_ORDER_DETAIL = "getSalesOrderDetail";

    public static final String GET_SHIPMENT_STATUS = "getShipmentStatus";

    public static final String GET_ACCOUNTS_RECEIVABLE = "getAccountsReceivable";

    public static final String GET_RECENT_SALES_ORDERS = "getRecentSalesOrders";

    public static final String GET_SALES_SUMMARY = "getSalesSummary";

    // ===== 仓库 =====
    public static final String GET_INVENTORY = "getInventory";

    public static final String GET_WAREHOUSE_STOCK = "getWarehouseStock";

    public static final String GET_STOCK_MOVEMENTS = "getStockMovements";

    public static final String GET_RECENT_STOCK_MOVEMENTS = "getRecentStockMovements";

    public static final String GET_LOW_STOCK_ALERTS = "getLowStockAlerts";

    // ===== 采购 =====
    public static final String GET_PURCHASE_ORDERS = "getPurchaseOrders";

    public static final String GET_PURCHASE_ORDER_DETAIL = "getPurchaseOrderDetail";

    public static final String GET_PURCHASE_RECEIVE_STATUS = "getPurchaseReceiveStatus";

    public static final String GET_RECENT_PURCHASE_ORDERS = "getRecentPurchaseOrders";

    public static final String GET_SUPPLIER_PAYABLE = "getSupplierPayable";

    // ===== 生产 =====
    public static final String GET_WORK_ORDER_STATUS = "getWorkOrderStatus";

    public static final String GET_PRODUCTION_ORDERS = "getProductionOrders";

    public static final String GET_WORK_ORDER_MATERIALS = "getWorkOrderMaterials";

    public static final String GET_RECENT_WORK_ORDERS = "getRecentWorkOrders";

    public static final String GET_WORK_ORDER_ROUTING = "getWorkOrderRouting";

    // ===== 质检 =====
    public static final String GET_QUALITY_INSPECTION = "getQualityInspection";

    public static final String GET_QUALITY_RECORDS = "getQualityRecords";

    public static final String GET_DEFECT_DETAILS = "getDefectDetails";

    public static final String GET_RECENT_QUALITY_RECORDS = "getRecentQualityRecords";

    public static final String GET_QUALITY_RATE = "getQualityRate";

    // ===== 财务 =====
    public static final String GET_MONTHLY_FINANCE_SUMMARY = "getMonthlyFinanceSummary";

    public static final String GET_LEDGER_BY_ACCOUNT = "getLedgerByAccount";

    public static final String GET_RECENT_LEDGER = "getRecentLedger";

    public static final String GET_PAYMENT_RECORDS = "getPaymentRecords";

    public static final String GET_RECENT_PAYMENTS = "getRecentPayments";

    // ===== 售后 =====
    public static final String GET_AFTER_SALES_TICKETS = "getAfterSalesTickets";

    public static final String GET_TICKET_DETAIL = "getTicketDetail";

    public static final String GET_RECENT_TICKETS = "getRecentTickets";

    public static final String GET_PRODUCT_RETURNS = "getProductReturns";

    // ===== 委外 =====
    public static final String GET_OUTSOURCING_ORDERS = "getOutsourcingOrders";

    public static final String GET_OUTSOURCING_ORDER_DETAIL = "getOutsourcingOrderDetail";

    public static final String GET_RECENT_OUTSOURCING_ORDERS = "getRecentOutsourcingOrders";

    public static final String GET_OUTSOURCING_MATERIAL_FLOW = "getOutsourcingMaterialFlow";

    /**
     * 系统内部 Tool：图表方案选择。
     * <p>
     * 它不面向业务查询，而是让模型只输出「图表类型 + 标题」，字段绑定与聚合全部后端生成。
     * 动态 Tool 不得占用此名，否则模型会收到两个同名函数，行为不可预期。
     */
    public static final String CHART_PLAN = "plan_chart_visualization";

    /** 系统保留名称集合，动态 Tool 配置时一律拒绝 */
    private static final Set<String> RESERVED = Set.of(CHART_PLAN);

    /**
     * 判断名称是否为系统保留。
     */
    public static boolean isReserved(String toolName) {
        return RESERVED.contains(toolName);
    }

    private ToolNames() {
    }

}
