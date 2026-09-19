package com.duduke.erp.service.tool;

import java.util.HashMap;
import java.util.Map;

/**
 * Tool 与权限码的对应表。
 * <p>
 * 把「哪个 Tool 需要哪个权限」集中在一处，而不是散在每个 Tool 类上：
 * 注册表要按用户权限过滤可见 Tool，必须能<b>一次性</b>问出映射关系。
 * <p>
 * 每个 Tool 都必须在此声明权限，否则注册表会拒绝注册它并记 ERROR
 * （fail-closed，见 {@code ToolRegistryService#refresh}）。
 * 漏声明的后果是「模型用不了这个 Tool」，而不是「谁都能用」。
 */
public final class ToolPermissionCatalog {

    // ===== 模块级权限码 =====
    public static final String SALES = "tool:sales:query";

    public static final String PURCHASE = "tool:purchase:query";

    public static final String PRODUCTION = "tool:production:query";

    public static final String QUALITY = "tool:quality:query";

    public static final String WAREHOUSE = "tool:warehouse:query";

    public static final String FINANCE = "tool:finance:query";

    public static final String AFTER_SALES = "tool:aftersales:query";

    public static final String OUTSOURCING = "tool:outsourcing:query";

    /**
     * 动态 SQL Tool 的调用权限。
     * <p>
     * 与模块级权限码分开，因为它的风险面完全不同：动态 Tool 是管理端配置的
     * <b>任意单层 SELECT</b>（可查任意表），而不是我们逐个审过的固定查询。
     * 因此设一道独立闸门——只把「定义动态 Tool 的权力」（tool:llm:save）
     * 与「使用全部动态 Tool 的权力」给到同一批人，不默认扩散给所有能提问的人。
     */
    public static final String DYNAMIC = "tool:dynamic:query";

    /**
     * 知识库检索工具的权限码。
     * <p>
     * 与 {@code knowledge:manage}（知识库的增删改）分开：后者是运维知识库本身的权力，
     * 这个是「在对话里检索资料」的权力——能提问的人大多应该能用，但不该顺带获得管理权限。
     */
    public static final String KNOWLEDGE = "tool:knowledge:query";

    /** Tool 名 → 所需权限码 */
    private static final Map<String, String> BY_TOOL_NAME = build();

    /**
     * 取某个代码 Tool 所需权限；未声明返回 null（注册表据此拒绝注册）。
     */
    public static String forToolName(String toolName) {
        return BY_TOOL_NAME.get(toolName);
    }

    /** 已声明权限的 Tool 名集合，便于测试核对覆盖率 */
    public static java.util.Set<String> declaredToolNames() {
        return BY_TOOL_NAME.keySet();
    }

    private static Map<String, String> build() {
        Map<String, String> map = new HashMap<>();
        register(map, SALES,
                ToolNames.GET_SALES_ORDERS, ToolNames.GET_SALES_ORDER_DETAIL,
                ToolNames.GET_SHIPMENT_STATUS, ToolNames.GET_ACCOUNTS_RECEIVABLE,
                ToolNames.GET_RECENT_SALES_ORDERS, ToolNames.GET_SALES_SUMMARY);
        register(map, PURCHASE,
                ToolNames.GET_PURCHASE_ORDERS, ToolNames.GET_PURCHASE_ORDER_DETAIL,
                ToolNames.GET_PURCHASE_RECEIVE_STATUS, ToolNames.GET_RECENT_PURCHASE_ORDERS,
                ToolNames.GET_SUPPLIER_PAYABLE);
        register(map, PRODUCTION,
                ToolNames.GET_WORK_ORDER_STATUS, ToolNames.GET_PRODUCTION_ORDERS,
                ToolNames.GET_WORK_ORDER_MATERIALS, ToolNames.GET_RECENT_WORK_ORDERS,
                ToolNames.GET_WORK_ORDER_ROUTING);
        register(map, QUALITY,
                ToolNames.GET_QUALITY_INSPECTION, ToolNames.GET_QUALITY_RECORDS,
                ToolNames.GET_DEFECT_DETAILS, ToolNames.GET_RECENT_QUALITY_RECORDS,
                ToolNames.GET_QUALITY_RATE);
        register(map, WAREHOUSE,
                ToolNames.GET_INVENTORY, ToolNames.GET_WAREHOUSE_STOCK,
                ToolNames.GET_STOCK_MOVEMENTS, ToolNames.GET_RECENT_STOCK_MOVEMENTS,
                ToolNames.GET_LOW_STOCK_ALERTS);
        register(map, FINANCE,
                ToolNames.GET_MONTHLY_FINANCE_SUMMARY, ToolNames.GET_LEDGER_BY_ACCOUNT,
                ToolNames.GET_RECENT_LEDGER, ToolNames.GET_PAYMENT_RECORDS,
                ToolNames.GET_RECENT_PAYMENTS);
        register(map, AFTER_SALES,
                ToolNames.GET_AFTER_SALES_TICKETS, ToolNames.GET_TICKET_DETAIL,
                ToolNames.GET_RECENT_TICKETS, ToolNames.GET_PRODUCT_RETURNS);
        register(map, OUTSOURCING,
                ToolNames.GET_OUTSOURCING_ORDERS, ToolNames.GET_OUTSOURCING_ORDER_DETAIL,
                ToolNames.GET_RECENT_OUTSOURCING_ORDERS, ToolNames.GET_OUTSOURCING_MATERIAL_FLOW);
        // 知识库检索：不属于任何一个业务模块，单独一个权限码
        register(map, KNOWLEDGE, ToolNames.SEARCH_KNOWLEDGE_BASE);
        return Map.copyOf(map);
    }

    private static void register(Map<String, String> map, String permission, String... toolNames) {
        for (String name : toolNames) {
            String previous = map.put(name, permission);
            if (previous != null) {
                // 同名 Tool 归属两个模块必然是复制粘贴错误，早失败早发现
                throw new IllegalStateException(
                        "Tool 名称重复声明：" + name + "（" + previous + " 与 " + permission + "）");
            }
        }
    }

    private ToolPermissionCatalog() {
    }

}
