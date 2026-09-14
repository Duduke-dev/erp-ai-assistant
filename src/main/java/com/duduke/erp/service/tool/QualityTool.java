package com.duduke.erp.service.tool;

import java.util.List;
import java.util.Map;

import com.duduke.erp.mapper.QualityToolMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 质检模块 Tool。约定同 {@link SalesTool}。
 */
@Component
@RequiredArgsConstructor
public class QualityTool implements BusinessTool {

    private final QualityToolMapper qualityToolMapper;

    @Tool(name = ToolNames.GET_QUALITY_INSPECTION,
          description = "根据批次号查询质检结果，返回检验单号、产品、抽样数量、合格数量、不良数量与判定结果")
    public List<Map<String, Object>> getQualityInspection(
            @ToolParam(description = "批次号，精确匹配") String batchNo) {
        return this.qualityToolMapper.selectInspectionByBatch(batchNo);
    }

    @Tool(name = ToolNames.GET_QUALITY_RECORDS,
          description = "根据产品名称查询质检记录列表，返回检验单号、批次、抽样/合格/不良数量与判定结果")
    public List<Map<String, Object>> getQualityRecords(
            @ToolParam(description = "产品名称，支持模糊匹配") String productName) {
        return this.qualityToolMapper.selectRecordsByProduct(ToolParams.like(productName));
    }

    @Tool(name = ToolNames.GET_DEFECT_DETAILS,
          description = "根据批次号查询质检不良明细，返回不良类型、不良数量与备注")
    public List<Map<String, Object>> getDefectDetails(
            @ToolParam(description = "批次号，精确匹配") String batchNo) {
        return this.qualityToolMapper.selectDefectsByBatch(batchNo);
    }

    @Tool(name = ToolNames.GET_RECENT_QUALITY_RECORDS,
          description = "按检验日期范围查询质检记录。可用于回答「最近 / 本周 / 本月 / 本季度 / 今年」"
                  + "等时间段的质检情况——调用方需自行把自然语言时间段换算成起止日期")
    public List<Map<String, Object>> getRecentQualityRecords(
            @ToolParam(description = "开始日期，格式 yyyy-MM-dd") String startDate,
            @ToolParam(description = "结束日期，格式 yyyy-MM-dd") String endDate) {
        return this.qualityToolMapper.selectRecordsByDateRange(
                ToolParams.parseDate(startDate, "开始日期"),
                ToolParams.parseDate(endDate, "结束日期"));
    }

    @Tool(name = ToolNames.GET_QUALITY_RATE,
          description = "按时间范围统计质检合格率，返回检验单数、抽样总数、合格总数、不良总数与合格率（百分比）")
    public List<Map<String, Object>> getQualityRate(
            @ToolParam(description = "开始日期，格式 yyyy-MM-dd") String startDate,
            @ToolParam(description = "结束日期，格式 yyyy-MM-dd") String endDate) {
        return this.qualityToolMapper.selectQualityRate(
                ToolParams.parseDate(startDate, "开始日期"),
                ToolParams.parseDate(endDate, "结束日期"));
    }

}
