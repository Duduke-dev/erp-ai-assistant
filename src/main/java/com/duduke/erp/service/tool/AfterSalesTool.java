package com.duduke.erp.service.tool;

import java.util.List;
import java.util.Map;

import com.duduke.erp.mapper.AfterSalesToolMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 售后模块 Tool。约定同 {@link SalesTool}。
 */
@Component
@RequiredArgsConstructor
public class AfterSalesTool implements BusinessTool {

    private final AfterSalesToolMapper afterSalesToolMapper;

    @Tool(name = ToolNames.GET_AFTER_SALES_TICKETS,
          description = "根据客户名称查询售后工单记录，返回工单号、产品、问题类型、受理日期与处理状态")
    public List<Map<String, Object>> getAfterSalesTickets(
            @ToolParam(description = "客户名称，支持模糊匹配") String customerName) {
        return this.afterSalesToolMapper.selectTicketsByCustomer(ToolParams.like(customerName));
    }

    @Tool(name = ToolNames.GET_TICKET_DETAIL,
          description = "根据售后工单号查询工单详情，返回客户、产品、问题类型、受理日期、处理状态与问题描述")
    public List<Map<String, Object>> getTicketDetail(
            @ToolParam(description = "售后工单号，精确匹配") String ticketNo) {
        return this.afterSalesToolMapper.selectTicketByNo(ticketNo);
    }

    @Tool(name = ToolNames.GET_RECENT_TICKETS,
          description = "按受理日期范围查询售后工单。可用于回答「最近 / 本周 / 本月 / 本季度 / 今年」"
                  + "等时间段的售后情况——调用方需自行把自然语言时间段换算成起止日期")
    public List<Map<String, Object>> getRecentTickets(
            @ToolParam(description = "开始日期，格式 yyyy-MM-dd") String startDate,
            @ToolParam(description = "结束日期，格式 yyyy-MM-dd") String endDate) {
        return this.afterSalesToolMapper.selectTicketsByDateRange(
                ToolParams.parseDate(startDate, "开始日期"),
                ToolParams.parseDate(endDate, "结束日期"));
    }

    @Tool(name = ToolNames.GET_PRODUCT_RETURNS,
          description = "根据产品名称查询售后退换货记录，返回退货单号、关联订单、客户、退换类型、数量、日期与原因")
    public List<Map<String, Object>> getProductReturns(
            @ToolParam(description = "产品名称，支持模糊匹配") String productName) {
        return this.afterSalesToolMapper.selectReturnsByProduct(ToolParams.like(productName));
    }

}
