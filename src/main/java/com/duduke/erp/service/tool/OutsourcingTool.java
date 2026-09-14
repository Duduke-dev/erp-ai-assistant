package com.duduke.erp.service.tool;

import java.util.List;
import java.util.Map;

import com.duduke.erp.mapper.OutsourcingToolMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 委外模块 Tool。约定同 {@link SalesTool}。
 */
@Component
@RequiredArgsConstructor
public class OutsourcingTool implements BusinessTool {

    private final OutsourcingToolMapper outsourcingToolMapper;

    @Tool(name = ToolNames.GET_OUTSOURCING_ORDERS,
          description = "根据供应商名称查询委外加工订单，返回委外单号、产品、数量、下单日期与状态")
    public List<Map<String, Object>> getOutsourcingOrders(
            @ToolParam(description = "供应商名称，支持模糊匹配") String supplierName) {
        return this.outsourcingToolMapper.selectOrdersBySupplier(ToolParams.like(supplierName));
    }

    @Tool(name = ToolNames.GET_OUTSOURCING_ORDER_DETAIL,
          description = "根据委外单号查询订单详情，返回供应商、产品、数量、下单日期与状态")
    public List<Map<String, Object>> getOutsourcingOrderDetail(
            @ToolParam(description = "委外单号，精确匹配") String orderNo) {
        return this.outsourcingToolMapper.selectOrderByNo(orderNo);
    }

    @Tool(name = ToolNames.GET_RECENT_OUTSOURCING_ORDERS,
          description = "按下单日期范围查询委外加工订单。可用于回答「最近 / 本周 / 本月 / 本季度 / 今年」"
                  + "等时间段的委外单——调用方需自行把自然语言时间段换算成起止日期")
    public List<Map<String, Object>> getRecentOutsourcingOrders(
            @ToolParam(description = "开始日期，格式 yyyy-MM-dd") String startDate,
            @ToolParam(description = "结束日期，格式 yyyy-MM-dd") String endDate) {
        return this.outsourcingToolMapper.selectOrdersByDateRange(
                ToolParams.parseDate(startDate, "开始日期"),
                ToolParams.parseDate(endDate, "结束日期"));
    }

    @Tool(name = ToolNames.GET_OUTSOURCING_MATERIAL_FLOW,
          description = "根据委外单号查询来料与退料流水，返回产品、流水类型、数量与日期")
    public List<Map<String, Object>> getOutsourcingMaterialFlow(
            @ToolParam(description = "委外单号，精确匹配") String orderNo) {
        return this.outsourcingToolMapper.selectMaterialFlow(orderNo);
    }

}
