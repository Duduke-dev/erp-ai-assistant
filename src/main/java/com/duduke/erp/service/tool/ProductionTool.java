package com.duduke.erp.service.tool;

import java.util.List;
import java.util.Map;

import com.duduke.erp.mapper.ProductionToolMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 生产模块 Tool。约定同 {@link SalesTool}。
 */
@Component
@RequiredArgsConstructor
public class ProductionTool implements BusinessTool {

    private final ProductionToolMapper productionToolMapper;

    @Tool(name = ToolNames.GET_WORK_ORDER_STATUS,
          description = "根据生产工单号查询生产进度，返回产品、计划数量、完成数量、报废数量、状态与计划起止日期")
    public List<Map<String, Object>> getWorkOrderStatus(
            @ToolParam(description = "生产工单号，精确匹配") String orderNo) {
        return this.productionToolMapper.selectWorkOrderStatus(orderNo);
    }

    @Tool(name = ToolNames.GET_PRODUCTION_ORDERS,
          description = "根据产品名称查询生产工单列表，返回工单号、计划完成数量、状态与计划起止日期")
    public List<Map<String, Object>> getProductionOrders(
            @ToolParam(description = "产品名称，支持模糊匹配") String productName) {
        return this.productionToolMapper.selectOrdersByProduct(ToolParams.like(productName));
    }

    @Tool(name = ToolNames.GET_WORK_ORDER_MATERIALS,
          description = "根据生产工单号查询用料领料情况，返回物料名称、需求数量与已领数量")
    public List<Map<String, Object>> getWorkOrderMaterials(
            @ToolParam(description = "生产工单号，精确匹配") String orderNo) {
        return this.productionToolMapper.selectMaterialsByOrder(orderNo);
    }

    @Tool(name = ToolNames.GET_RECENT_WORK_ORDERS,
          description = "按计划开工日期范围查询生产工单。可用于回答「最近 / 本周 / 本月 / 本季度 / 今年」"
                  + "等时间段的工单——调用方需自行把自然语言时间段换算成起止日期")
    public List<Map<String, Object>> getRecentWorkOrders(
            @ToolParam(description = "开始日期，格式 yyyy-MM-dd") String startDate,
            @ToolParam(description = "结束日期，格式 yyyy-MM-dd") String endDate) {
        return this.productionToolMapper.selectOrdersByDateRange(
                ToolParams.parseDate(startDate, "开始日期"),
                ToolParams.parseDate(endDate, "结束日期"));
    }

    @Tool(name = ToolNames.GET_WORK_ORDER_ROUTING,
          description = "根据生产工单号查询工序与报工状态，返回工序号、工序名称、工作中心与状态")
    public List<Map<String, Object>> getWorkOrderRouting(
            @ToolParam(description = "生产工单号，精确匹配") String orderNo) {
        return this.productionToolMapper.selectRoutingByOrder(orderNo);
    }

}
