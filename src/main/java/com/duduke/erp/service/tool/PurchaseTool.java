package com.duduke.erp.service.tool;

import java.util.List;
import java.util.Map;

import com.duduke.erp.mapper.PurchaseToolMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 采购模块 Tool。约定同 {@link SalesTool}：只做入参规范化与转发，
 * 不写租户条件、不做权限判断（权限在注册阶段按模块过滤）。
 */
@Component
@RequiredArgsConstructor
public class PurchaseTool implements BusinessTool {

    private final PurchaseToolMapper purchaseToolMapper;

    @Tool(name = ToolNames.GET_PURCHASE_ORDERS,
          description = "根据供应商名称查询采购订单列表，返回采购单号、下单日期、供应商、总金额与状态")
    public List<Map<String, Object>> getPurchaseOrders(
            @ToolParam(description = "供应商名称，支持模糊匹配") String supplierName) {
        return this.purchaseToolMapper.selectOrdersBySupplier(ToolParams.like(supplierName));
    }

    @Tool(name = ToolNames.GET_PURCHASE_ORDER_DETAIL,
          description = "根据采购单号查询订单详情，含表头与全部物料明细（产品、订购数量、已收数量、单价、金额）")
    public List<Map<String, Object>> getPurchaseOrderDetail(
            @ToolParam(description = "采购单号，精确匹配") String orderNo) {
        return this.purchaseToolMapper.selectOrderDetail(orderNo);
    }

    @Tool(name = ToolNames.GET_PURCHASE_RECEIVE_STATUS,
          description = "根据采购单号查询到货收货记录，返回收货单号、产品、收货日期与数量")
    public List<Map<String, Object>> getPurchaseReceiveStatus(
            @ToolParam(description = "采购单号，精确匹配") String orderNo) {
        return this.purchaseToolMapper.selectReceiveByOrder(orderNo);
    }

    @Tool(name = ToolNames.GET_RECENT_PURCHASE_ORDERS,
          description = "按时间范围查询采购订单。可用于回答「最近 / 今天 / 本周 / 本月 / 本季度 / 今年」"
                  + "等时间段的采购单——调用方需自行把自然语言时间段换算成起止日期")
    public List<Map<String, Object>> getRecentPurchaseOrders(
            @ToolParam(description = "开始日期，格式 yyyy-MM-dd") String startDate,
            @ToolParam(description = "结束日期，格式 yyyy-MM-dd") String endDate) {
        return this.purchaseToolMapper.selectOrdersByDateRange(
                ToolParams.parseDate(startDate, "开始日期"),
                ToolParams.parseDate(endDate, "结束日期"));
    }

    @Tool(name = ToolNames.GET_SUPPLIER_PAYABLE,
          description = "查询供应商应付账款余额，返回应付总额、已付金额与未付余额，按余额降序")
    public List<Map<String, Object>> getSupplierPayable(
            @ToolParam(description = "供应商名称，支持模糊匹配") String supplierName) {
        return this.purchaseToolMapper.selectPayableBySupplier(ToolParams.like(supplierName));
    }

}
