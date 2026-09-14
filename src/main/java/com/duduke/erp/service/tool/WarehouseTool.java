package com.duduke.erp.service.tool;

import java.util.List;
import java.util.Map;

import com.duduke.erp.mapper.WarehouseToolMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 仓库模块 Tool —— 供模型调用的库存与出入库只读查询。
 * <p>
 * 与 {@link SalesTool} 同样是「规范化入参 → 交 Mapper → 返回原始行」，
 * 不写租户条件、不做权限判断。
 * <p>
 * 库存类查询返回的是<b>产品编码</b>（来自 JOIN {@code product}），
 * 因为编码才是 ERP 里的业务主键，模型只拿到名称没法继续追问。
 */
@Component
@RequiredArgsConstructor
public class WarehouseTool implements BusinessTool {

    private final WarehouseToolMapper warehouseToolMapper;

    @Tool(name = ToolNames.GET_INVENTORY,
          description = "查询指定产品的当前库存，返回产品编码、名称、仓库、库位、批次、数量与安全库存")
    public List<Map<String, Object>> getInventory(
            @ToolParam(description = "产品编码（精确）或产品名称（模糊）") String product) {
        return this.warehouseToolMapper.selectInventoryByProduct(
                product == null ? "" : product.trim(), ToolParams.like(product));
    }

    @Tool(name = ToolNames.GET_WAREHOUSE_STOCK,
          description = "查询指定仓库的全部库存明细，返回产品编码、名称、库位、批次、数量与安全库存")
    public List<Map<String, Object>> getWarehouseStock(
            @ToolParam(description = "仓库名称（精确）或仓库编码（模糊）") String warehouse) {
        return this.warehouseToolMapper.selectInventoryByWarehouse(
                warehouse == null ? "" : warehouse.trim(), ToolParams.like(warehouse));
    }

    @Tool(name = ToolNames.GET_STOCK_MOVEMENTS,
          description = "查询指定产品的出入库流水，返回流水号、类型、仓库、数量、日期与关联单号")
    public List<Map<String, Object>> getStockMovements(
            @ToolParam(description = "产品名称，支持模糊匹配") String product) {
        return this.warehouseToolMapper.selectMovementsByProduct(ToolParams.like(product));
    }

    @Tool(name = ToolNames.GET_RECENT_STOCK_MOVEMENTS,
          description = "按时间范围查询出入库流水。可用于回答「最近 / 今天 / 本周 / 本月 / 本季度 / 今年」"
                  + "等时间段的库存变动——调用方需自行把自然语言时间段换算成起止日期")
    public List<Map<String, Object>> getRecentStockMovements(
            @ToolParam(description = "开始日期，格式 yyyy-MM-dd") String startDate,
            @ToolParam(description = "结束日期，格式 yyyy-MM-dd") String endDate) {
        return this.warehouseToolMapper.selectMovementsByDateRange(
                ToolParams.parseDate(startDate, "开始日期"),
                ToolParams.parseDate(endDate, "结束日期"));
    }

    @Tool(name = ToolNames.GET_LOW_STOCK_ALERTS,
          description = "查询库存预警：数量低于安全库存的产品清单，返回产品编码、名称、仓库、当前数量、"
                  + "安全库存与缺口数量，按缺口降序")
    public List<Map<String, Object>> getLowStockAlerts() {
        return this.warehouseToolMapper.selectLowStockAlerts();
    }

}
