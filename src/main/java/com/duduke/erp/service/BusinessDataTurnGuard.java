package com.duduke.erp.service;

import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.duduke.erp.service.tool.trace.ToolCallRecord;
import com.duduke.erp.service.tool.trace.ToolCallRecorder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

/**
 * 当前轮业务数据守卫：禁止模型直接复用历史回答里的业务数字。
 *
 * <h3>要解决的问题</h3>
 * 多轮对话里，模型常拿上一轮已经算好的表格直接作答（"上轮显示销售额 55,000"），
 * 而用户问的其实是另一个筛选条件。这类回答看起来很像真的，因为数字确实来自本会话——
 * 但它<b>不是本轮查出来的</b>。守卫在「本轮本该查库却没拿到业务数据」时，
 * 换一段更强约束的提示词重试一次。
 *
 * <h3>判据：本轮是否取得业务数据</h3>
 * 直接用 M3.5 的 {@link ToolCallRecorder}——它按 traceId 聚合了本轮的每次 Tool 调用
 * （含 status 与 resultCount）。<b>「成功且结果非空」</b>才算取得业务数据：
 * 调用失败或查到 0 行都不算，这两种情况下模型手上同样没有本轮数据。
 * <p>
 * 参考实现是靠图表模块留存的结构化结果来判断的（{@code ToolResultRecorder}）。
 * 本项目不依赖那套结构：用 {@code ToolCallRecorder} 可达到同样的判定效果，且不需要为了守卫先建一整个模块。
 *
 * <h3>刻意不做的事</h3>
 * <ol>
 *   <li><b>不做流式门控</b>：参考实现在 {@code Flux} 上暂存首轮分片、确认数据后才放行，
 *       本项目流式走 {@code SseEmitter}，接进来要改 SSE 管线，属独立变更。</li>
 *   <li><b>不合并两次调用的 token 用量</b>：重建 {@code ChatResponseMetadata}
 *       会丢掉 RAG 写在元数据里的自定义键（引用与召回数依赖它），
 *       得不偿失。等 M5 计费真正需要精确用量时，再设计保留自定义键的合并方式。</li>
 *   <li><b>首轮不自动附加约束提示词</b>：那会给每一轮都增加固定 token 开销，
 *       而本地没有可用的模型 Key 无法验证其收益。仅在<b>触发重试</b>时加强约束。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BusinessDataTurnGuard {

    /** ERP 业务对象关键词 */
    private static final Pattern BUSINESS_TERM = Pattern.compile(
            "销售|订单|库存|仓库|仓储|工单|售后|采购|供应商|生产|质检|检验|客户|应收|应付|财务|发票|委外|物料|产品|货品|批次|账龄|金额|数量|合格率|余额"
                    + "|\\b(?:sales?|orders?|inventory|stock|warehouses?|work\\s*orders?|after[-\\s]?sales|"
                    + "purchases?|procurement|suppliers?|production|quality\\s*inspections?|customers?|"
                    + "accounts?\\s*receivable|accounts?\\s*payable|finance|invoices?|outsourcing|materials?|"
                    + "products?|goods|batches?|aging|amounts?|quantities?|balances?)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 查询、统计或展示意图关键词 */
    private static final Pattern DATA_INTENT = Pattern.compile(
            "查询|查找|查到|统计|分析|比较|对比|分布|趋势|占比|汇总|明细|列表|排名|排行|TOP|最多|最少|多少|哪些|对应|在哪|情况|进度|状态|怎么样|怎样|展示|图"
                    + "|\\b(?:query|search|find|show|display|list|count|statistics?|analy(?:ze|sis)|"
                    + "compar(?:e|ison)|distribution|trends?|percentages?|ratios?|summar(?:y|ize)|details?|"
                    + "rankings?|top|most|least|which|where|status|progress|latest|current|recent|totals?|"
                    + "averages?)\\b|how\\s+(?:many|much)",
            Pattern.CASE_INSENSITIVE);

    /** 英文直接请求业务记录的问句与祈使句 */
    private static final Pattern ENGLISH_DIRECT_DATA_REQUEST = Pattern.compile(
            "\\b(?:what\\s+(?:are|were)\\s+(?:the\\s+)?|(?:give|tell)\\s+me\\s+(?:(?:the|all|some)\\s+)?)"
                    + "(?:sales\\s+orders?|orders?|inventory|stock|warehouses?|work\\s*orders?|"
                    + "after[-\\s]?sales\\s+tickets?|purchases?|procurement\\s+orders?|production\\s+orders?|"
                    + "quality\\s+inspections?|accounts?\\s*receivable|accounts?\\s*payable|invoices?|"
                    + "batches?|aging\\s+reports?|balances?)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final String RETRY_PROMPT = """
            【当前轮业务查询重试】
            上一次回答没有取得本轮业务查询结果，不得继续使用历史回答中的数字或表格。
            必须先调用与当前问题匹配的业务查询工具；如缺少必要查询条件，请只使用业务语言说明需要补充的条件。

            【当前问题】
            %s
            """;

    private static final String STATUS_SUCCESS = "success";

    private final ToolCallRecorder toolCallRecorder;

    /**
     * 判断当前问题是否要求取得 ERP 业务数据。
     *
     * @param mode     问答模式；{@code data} 恒为 true，{@code knowledge} 恒为 false
     * @param question 用户问题
     */
    public boolean requiresCurrentBusinessData(String mode, String question) {
        if ("data".equals(mode)) {
            return true;
        }
        if (!"auto".equals(mode) || question == null) {
            return false;
        }
        return BUSINESS_TERM.matcher(question).find()
                && (DATA_INTENT.matcher(question).find()
                        || ENGLISH_DIRECT_DATA_REQUEST.matcher(question).find());
    }

    /**
     * 构造仅允许本轮业务查询的重试问题。
     */
    public String retryQuestion(String question) {
        return RETRY_PROMPT.formatted(question == null ? "" : question);
    }

    /**
     * 校验非流式业务问答是否取得本轮数据，缺失时<b>最多重试一次</b>。
     *
     * @param initialResponse 首次模型响应
     * @param retrySupplier   重试模型调用（内部应使用 {@link #retryQuestion(String)} 构造问题）
     * @param mode            问答模式
     * @param question        用户原始问题
     * @param traceId         本轮链路 ID
     * @return 最终采用的响应
     */
    public ChatResponse ensureNonStreaming(ChatResponse initialResponse,
                                           Supplier<ChatResponse> retrySupplier,
                                           String mode, String question, String traceId) {
        if (!requiresCurrentBusinessData(mode, question) || hasBusinessResult(traceId)) {
            return initialResponse;
        }
        logRetryReason(traceId, question);

        ChatResponse retryResponse = retrySupplier.get();
        if (retryResponse == null) {
            throw new IllegalStateException("业务数据查询重试未返回模型响应");
        }
        if (!hasBusinessResult(traceId)) {
            log.warn("本轮业务查询重试后仍无可用结构化结果: traceId={}, toolCallCount={}, question={}",
                    traceId, toolCallCount(traceId), question);
        }
        return retryResponse;
    }

    /**
     * 本轮是否已取得非空业务结果：有任意一次「成功且结果行数大于 0」的 Tool 调用。
     */
    public boolean hasBusinessResult(String traceId) {
        if (traceId == null) {
            return false;
        }
        for (ToolCallRecord record : this.toolCallRecorder.getResults(traceId)) {
            if (STATUS_SUCCESS.equals(record.status()) && record.resultCount() > 0) {
                return true;
            }
        }
        return false;
    }

    private void logRetryReason(String traceId, String question) {
        int count = toolCallCount(traceId);
        if (count == 0) {
            log.warn("本轮未调用业务 Tool，执行一次有界查询重试: traceId={}, question={}",
                    traceId, question);
            return;
        }
        log.warn("本轮业务 Tool 已调用但未取得非空结构化结果，执行一次有界查询重试: "
                + "traceId={}, toolCallCount={}", traceId, count);
    }

    private int toolCallCount(String traceId) {
        return this.toolCallRecorder.getResults(traceId).size();
    }

}
