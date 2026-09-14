package com.duduke.erp.service.tool.trace;

/**
 * 单条 Tool 调用摘要。
 * <p>
 * 用于一轮问答结束后汇总（写进 {@code chat_message.tool_calls}、
 * 以及 M4 判断「本轮有没有产生业务数据」），所以刻意<b>不存完整结果</b>——
 * 完整结果可能很大，且模型已经拿到了，没必要再留一份。
 *
 * @param toolName     Tool 名称
 * @param toolSource   code / database
 * @param arguments    模型传入的参数 JSON，可能是 null
 * @param status       success / error
 * @param resultCount  结果行数
 * @param elapsedMs    调用耗时
 * @param errorSummary 失败摘要，成功时为 null
 */
public record ToolCallRecord(String toolName, String toolSource, String arguments, String status,
                             int resultCount, long elapsedMs, String errorSummary) {
}
