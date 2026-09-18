package com.duduke.erp.service.tool.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.duduke.erp.entity.po.ToolCallLog;
import com.duduke.erp.tenant.TenantContext;

import lombok.RequiredArgsConstructor;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 给 ToolCallback 加调用流水与租户上下文保障的包装器。
 * <p>
 *  registry 装配快照时把每个 Tool 都包一层，于是「记录流水」和
 *  「恢复租户上下文」两件事对业务 Tool 完全透明。
 *
 * <h3>为什么要在这里恢复租户上下文</h3>
 * Tool 可能跑在模型回调的线程上（尤其是流式），那个线程的 ThreadLocal
 * 未必带租户信息。而 Tool 里的 Mapper 依赖租户插件自动注入条件——
 * 拿不到租户就会<b>直接抛异常</b>（我们的 TenantLineHandler 用的是 requireEntCode，
 * 宁可失败也不静默放过）。所以必须在调用前把上下文补上、调用后还原。
 * 还原用 finally 是硬要求：不做的话线程池复用会让后续请求串到本轮的租户上。
 *
 * <h3>流水是旁路</h3>
 * 写库失败只记 warn，绝不冒泡——正在进行的问答不能因为记不了流水而失败。
 *
 * <h3>已知边界：租户缺失时流水落不了库</h3>
 * 写 {@code tool_call_log} 同样依赖租户插件注入 {@code ent_code}，
 * 所以「拿不到租户」这一次失败本身也记不进流水（只留一条 warn）。
 * 好消息是<b>聚合器是内存结构</b>，仍会记下这次失败——
 * 本轮调用汇总与「有没有产生业务数据」的判断不受影响。
 * 正常链路里租户必然已设置，这条边界只在异常场景遇到。
 */
@RequiredArgsConstructor
public class LoggingToolCallback implements ToolCallback {

    /** Tool 来源：@Tool 方法 */
    public static final String SOURCE_CODE = "code";

    /** Tool 来源：数据库动态 SQL */
    public static final String SOURCE_DATABASE = "database";

    public static final String STATUS_SUCCESS = "success";

    public static final String STATUS_ERROR = "error";

    private final ToolCallback delegate;

    private final String toolSource;

    private final ToolCallRecorder recorder;

    private final ToolCallLogService logService;

    private final ObjectMapper objectMapper;

    @Override
    public ToolDefinition getToolDefinition() {
        return this.delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return this.delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        Map<String, Object> context = readContext(toolContext);
        String traceId = contextValue(context, ToolTraceKeys.TRACE_ID, ToolCallRecorder.createTraceId());
        String toolName = this.delegate.getToolDefinition().name();

        String previousEntCode = TenantContext.getEntCode();
        Long previousUserId = TenantContext.getUserId();
        applyTenantContext(context, previousEntCode, previousUserId);

        long startedAt = System.nanoTime();
        try {
            String result = this.delegate.call(toolInput, toolContext);
            record(context, traceId, toolName, toolInput, result,
                    elapsedMillis(startedAt), STATUS_SUCCESS, null);
            return result;
        }
        catch (RuntimeException e) {
            record(context, traceId, toolName, toolInput, null,
                    elapsedMillis(startedAt), STATUS_ERROR, e.getMessage());
            throw e;
        }
        finally {
            // 必须还原：线程复用会让后续请求串到本轮的租户/用户上
            TenantContext.set(previousEntCode, previousUserId);
        }
    }

    /**
     * 把 Tool 返回的 JSON 数组解析成行列表。
     * 只有「数组的数组元素都是对象」才认——非结构化输出（如一句话回执）视为无结果。
     */
    private List<Map<String, Object>> parseRows(String result) {
        if (result == null || result.isBlank()) {
            return List.of();
        }
        try {
            JsonNode tree = this.objectMapper.readTree(result);
            if (tree == null || !tree.isArray()) {
                return List.of();
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode node : tree) {
                if (!node.isObject()) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                node.propertyNames().forEach(name -> row.put(name, scalar(node.get(name))));
                rows.add(row);
            }
            return rows;
        }
        catch (RuntimeException e) {
            // 解析失败只是画不了图，绝不能影响问答主链路
            return List.of();
        }
    }

    private static Object scalar(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asString();
        }
        if (node.isNumber()) {
            return node.isIntegralNumber() ? (Object) node.asLong() : (Object) node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.toString();
    }

    /**
     * 从 ToolContext 取值。全部字段允许缺失——Tool 也可能在没有完整链路上下文时被调用。
     */
    public Map<String, Object> readContext(ToolContext toolContext) {
        return toolContext == null || toolContext.getContext() == null
                ? Map.of()
                : toolContext.getContext();
    }

    public String contextValue(Map<String, Object> context, String key, String defaultValue) {
        Object value = context.get(key);
        if (value == null || value.toString().isBlank()) {
            return defaultValue;
        }
        return value.toString();
    }

    /**
     * 根据返回 JSON 推断结果行数。
     * <p>
     * 我们的 Tool 一律返回 {@code List<Map>}，序列化后是 JSON 数组，所以只看数组长度；
     * 非数组（理论上不该出现）按 1 记，解析失败也按 1 记——
     * 拿不到准确数字时宁可记 1，不要记 0，否则会误导"这个 Tool 没查到东西"。
     */
    public int countResultRows(String result) {
        if (result == null || result.isBlank()) {
            return 0;
        }
        try {
            JsonNode parsed = this.objectMapper.readTree(result);
            return parsed.isArray() ? parsed.size() : 1;
        }
        catch (RuntimeException e) {
            return 1;
        }
    }

    private void applyTenantContext(Map<String, Object> context, String previousEntCode, Long previousUserId) {
        // 缺失时回落到调用前的值，而不是传 null——
        // 我们的 TenantContext.set 语义是「传 null 即清除」，直接传 null 会把上下文抹掉
        String entCode = contextValue(context, ToolTraceKeys.ENT_CODE, previousEntCode);
        Long userId = parseUserId(contextValue(context, ToolTraceKeys.USER_ID, null), previousUserId);
        TenantContext.set(entCode, userId);
    }

    private Long parseUserId(String raw, Long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.valueOf(raw.trim());
        }
        catch (NumberFormatException e) {
            return fallback;
        }
    }

    private long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    private void record(Map<String, Object> context, String traceId, String toolName, String toolInput,
                        String result, long elapsedMs, String status, String errorMessage) {
        int resultCount = countResultRows(result);
        this.recorder.record(traceId, new ToolCallRecord(toolName, this.toolSource, toolInput,
                status, resultCount, elapsedMs, errorMessage));
        this.logService.save(toLogEntity(context, traceId, toolName, toolInput,
                resultCount, elapsedMs, status, errorMessage));
    }

    private ToolCallLog toLogEntity(Map<String, Object> context, String traceId, String toolName,
                                    String toolInput, int resultCount, long elapsedMs,
                                    String status, String errorMessage) {
        ToolCallLog entry = new ToolCallLog();
        entry.setTraceId(traceId);
        entry.setConversationId(contextValue(context, ToolTraceKeys.CONVERSATION_ID, null));
        entry.setToolName(toolName);
        entry.setToolSource(this.toolSource);
        entry.setUserId(parseUserId(contextValue(context, ToolTraceKeys.USER_ID, null),
                TenantContext.getUserId()));
        entry.setMode(contextValue(context, ToolTraceKeys.MODE, null));
        entry.setModelName(contextValue(context, ToolTraceKeys.MODEL, null));
        entry.setArguments(toolInput);
        entry.setResultCount(resultCount);
        entry.setElapsedMs(elapsedMs);
        entry.setStatus(status);
        entry.setErrorSummary(errorMessage);
        return entry;
    }

}
