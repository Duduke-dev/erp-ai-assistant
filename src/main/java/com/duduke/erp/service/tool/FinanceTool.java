package com.duduke.erp.service.tool;

import java.util.List;
import java.util.Map;

import com.duduke.erp.mapper.FinanceToolMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 财务模块 Tool。约定同 {@link SalesTool}。
 * <p>
 * 应收在销售模块、应付在采购模块（业务归属更贴切），此处只覆盖总账与收付款。
 */
@Component
@RequiredArgsConstructor
public class FinanceTool implements BusinessTool {

    private final FinanceToolMapper financeToolMapper;

    @Tool(name = ToolNames.GET_MONTHLY_FINANCE_SUMMARY,
          description = "按月份统计财务收支，返回该月收入合计、支出合计与净额")
    public List<Map<String, Object>> getMonthlyFinanceSummary(
            @ToolParam(description = "月份，格式 yyyy-MM，例如 2026-09") String month) {
        return this.financeToolMapper.selectMonthlySummary(requireMonth(month));
    }

    @Tool(name = ToolNames.GET_LEDGER_BY_ACCOUNT,
          description = "根据会计科目名称查询总账明细，返回日期、科目、收入金额、支出金额与往来单位")
    public List<Map<String, Object>> getLedgerByAccount(
            @ToolParam(description = "会计科目名称，支持模糊匹配") String accountItem) {
        return this.financeToolMapper.selectLedgerByAccount(ToolParams.like(accountItem));
    }

    @Tool(name = ToolNames.GET_RECENT_LEDGER,
          description = "按时间范围查询财务总账明细。可用于回答「最近 / 本周 / 本月 / 本季度 / 今年」"
                  + "等时间段的收支——调用方需自行把自然语言时间段换算成起止日期")
    public List<Map<String, Object>> getRecentLedger(
            @ToolParam(description = "开始日期，格式 yyyy-MM-dd") String startDate,
            @ToolParam(description = "结束日期，格式 yyyy-MM-dd") String endDate) {
        return this.financeToolMapper.selectLedgerByDateRange(
                ToolParams.parseDate(startDate, "开始日期"),
                ToolParams.parseDate(endDate, "结束日期"));
    }

    @Tool(name = ToolNames.GET_PAYMENT_RECORDS,
          description = "根据客户名称查询收款记录，返回收款单号、关联订单、收款日期、金额与收款方式")
    public List<Map<String, Object>> getPaymentRecords(
            @ToolParam(description = "客户名称，支持模糊匹配") String customerName) {
        return this.financeToolMapper.selectPaymentsByCustomer(ToolParams.like(customerName));
    }

    @Tool(name = ToolNames.GET_RECENT_PAYMENTS,
          description = "按收款日期范围查询收款记录。可用于回答「最近 / 本周 / 本月 / 本季度 / 今年」"
                  + "等时间段的收款——调用方需自行把自然语言时间段换算成起止日期")
    public List<Map<String, Object>> getRecentPayments(
            @ToolParam(description = "开始日期，格式 yyyy-MM-dd") String startDate,
            @ToolParam(description = "结束日期，格式 yyyy-MM-dd") String endDate) {
        return this.financeToolMapper.selectPaymentsByDateRange(
                ToolParams.parseDate(startDate, "开始日期"),
                ToolParams.parseDate(endDate, "结束日期"));
    }

    /**
     * 月份参数校验。{@code yyyy-MM} 不是 ISO 日期，不能用 {@code parseDate}——
     * 这里只做格式校验，非法时抛出可读错误让模型改正。
     */
    private String requireMonth(String raw) {
        if (raw == null || !raw.trim().matches("\\d{4}-\\d{2}")) {
            throw new IllegalArgumentException("月份格式应为 yyyy-MM，例如 2026-09，实际传入：" + raw);
        }
        return raw.trim();
    }

}
