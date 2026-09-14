package com.duduke.erp.service.tool;

import java.util.List;
import java.util.Map;

import com.duduke.erp.mapper.SalesToolMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 销售模块 Tool —— 供模型调用的销售业务只读查询。
 * <p>
 * <b>用 {@code @Component} 而非 {@code @Service}</b>：Tool 不编排业务，
 * 它是把「查询能力」适配给模型的入口，与 {@code service/} 下有编排职责的服务语义不同。
 * <p>
 * 每个方法都只做三件事：规范化入参 → 交给 Mapper → 返回原始行。
 * <b>不做任何权限判断</b>——权限在注册阶段按模块过滤（无权限的工具模型根本看不到），
 * 在这里再判一次是重复且会拖慢每次调用。
 * <p>
 * <b>不写租户条件</b>：{@code ent_code} 由租户插件在 SQL 层自动注入。
 */
@Component
@RequiredArgsConstructor
public class SalesTool implements BusinessTool {

    private final SalesToolMapper salesToolMapper;

    @Tool(name = ToolNames.GET_SALES_ORDERS,
          description = "根据客户名称查询销售订单列表，返回订单号、下单日期、客户名称、总金额与订单状态")
    public List<Map<String, Object>> getSalesOrders(
            @ToolParam(description = "客户名称，支持模糊匹配") String customerName) {
        return this.salesToolMapper.selectOrdersByCustomer(ToolParams.like(customerName));
    }

    @Tool(name = ToolNames.GET_SALES_ORDER_DETAIL,
          description = "根据销售订单号查询订单详情，含表头（日期、客户、状态）与全部明细行（产品、数量、单价、金额）")
    public List<Map<String, Object>> getSalesOrderDetail(
            @ToolParam(description = "销售订单号，精确匹配") String orderNo) {
        return this.salesToolMapper.selectOrderDetail(orderNo);
    }

    @Tool(name = ToolNames.GET_SHIPMENT_STATUS,
          description = "根据销售订单号查询发货与物流信息，返回发货单号、发货日期、数量、物流单号与状态")
    public List<Map<String, Object>> getShipmentStatus(
            @ToolParam(description = "销售订单号，精确匹配") String orderNo) {
        return this.salesToolMapper.selectShipmentByOrder(orderNo);
    }

    @Tool(name = ToolNames.GET_ACCOUNTS_RECEIVABLE,
          description = "查询客户应收账款余额，返回应收总额、已收金额与未收余额，按余额降序")
    public List<Map<String, Object>> getAccountsReceivable(
            @ToolParam(description = "客户名称，支持模糊匹配") String customerName) {
        return this.salesToolMapper.selectReceivableByCustomer(ToolParams.like(customerName));
    }

    @Tool(name = ToolNames.GET_RECENT_SALES_ORDERS,
          description = "按时间范围查询销售订单列表。可用于回答「最近 / 今天 / 本周 / 本月 / 本季度 / 今年」"
                  + "等时间段的订单查询——调用方需自行把自然语言时间段换算成起止日期")
    public List<Map<String, Object>> getRecentSalesOrders(
            @ToolParam(description = "开始日期，格式 yyyy-MM-dd") String startDate,
            @ToolParam(description = "结束日期，格式 yyyy-MM-dd") String endDate) {
        return this.salesToolMapper.selectOrdersByDateRange(
                ToolParams.parseDate(startDate, "开始日期"),
                ToolParams.parseDate(endDate, "结束日期"));
    }

    @Tool(name = ToolNames.GET_SALES_SUMMARY,
          description = "按时间范围统计销售汇总，返回订单数、总金额与下单客户数")
    public List<Map<String, Object>> getSalesSummary(
            @ToolParam(description = "开始日期，格式 yyyy-MM-dd") String startDate,
            @ToolParam(description = "结束日期，格式 yyyy-MM-dd") String endDate) {
        return this.salesToolMapper.selectSalesSummary(
                ToolParams.parseDate(startDate, "开始日期"),
                ToolParams.parseDate(endDate, "结束日期"));
    }

}
