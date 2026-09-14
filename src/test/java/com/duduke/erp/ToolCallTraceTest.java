package com.duduke.erp;

import java.util.List;
import java.util.Map;

import com.duduke.erp.entity.po.ToolCallLog;
import com.duduke.erp.mapper.ToolCallLogMapper;
import com.duduke.erp.service.tool.ToolNames;
import com.duduke.erp.service.tool.ToolRegistryService;
import com.duduke.erp.service.tool.ToolSnapshot;
import com.duduke.erp.service.tool.trace.LoggingToolCallback;
import com.duduke.erp.service.tool.trace.ToolCallLogService;
import com.duduke.erp.service.tool.trace.ToolCallRecord;
import com.duduke.erp.service.tool.trace.ToolCallRecorder;
import com.duduke.erp.service.tool.trace.ToolTraceKeys;
import com.duduke.erp.tenant.TenantContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tool 调用追踪验证。
 * <p>
 * 三条不变量：
 * <ol>
 *   <li>调用会落 {@code tool_call_log}，且同 traceId 能在 {@code ToolCallRecorder} 里聚合；</li>
 *   <li><b>租户上下文会被补上、并在 finally 还原</b>——
 *       不还原的话线程复用会让后续请求串到本轮租户上，属于最难排查的一类问题；</li>
 *   <li><b>流水写库失败不能冒泡</b>——旁路数据不能影响正在进行的问答。</li>
 * </ol>
 */
@SpringBootTest
class ToolCallTraceTest {

    private static final String TENANT = "DEMO";

    @Autowired
    private ToolRegistryService registry;

    @Autowired
    private ToolCallRecorder recorder;

    @Autowired
    private ToolCallLogService logService;

    @Autowired
    private ToolCallLogMapper logMapper;

    /** 本用例产生的 traceId，用于清理日志 */
    private final java.util.List<String> createdTraceIds = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        for (String traceId : this.createdTraceIds) {
            purgeLogs(traceId);
            this.recorder.clearTrace(traceId);
        }
        this.createdTraceIds.clear();
        TenantContext.clear();
    }

    @Test
    @DisplayName("Tool 调用落流水，并按 traceId 聚合")
    void recordsCallLogAndAggregates() {
        ToolCallback callback = findCallback(ToolNames.GET_SALES_ORDERS);
        String traceId = ToolCallRecorder.createTraceId();
        this.createdTraceIds.add(traceId);

        callback.call("{\"customerName\":\"任意客户\"}", contextOf(traceId));

        // 1) 聚合器里有这一轮的记录
        List<ToolCallRecord> records = this.recorder.getResults(traceId);
        assertThat(records).hasSize(1);
        ToolCallRecord record = records.get(0);
        assertThat(record.toolName()).isEqualTo(ToolNames.GET_SALES_ORDERS);
        assertThat(record.toolSource()).isEqualTo(LoggingToolCallback.SOURCE_CODE);
        assertThat(record.status()).isEqualTo(LoggingToolCallback.STATUS_SUCCESS);

        // 2) 库里有这条流水
        List<ToolCallLog> logs = logsOf(traceId);
        assertThat(logs).hasSize(1);
        ToolCallLog log = logs.get(0);
        assertThat(log.getToolName()).isEqualTo(ToolNames.GET_SALES_ORDERS);
        assertThat(log.getToolSource()).isEqualTo(LoggingToolCallback.SOURCE_CODE);
        assertThat(log.getEntCode()).isEqualTo(TENANT);
        assertThat(log.getUserId()).isEqualTo(1L);
        assertThat(log.getMode()).isEqualTo("auto");
        assertThat(log.getStatus()).isEqualTo(LoggingToolCallback.STATUS_SUCCESS);
        assertThat(log.getElapsedMs()).isNotNegative();
    }

    @Test
    @DisplayName("调用前补上租户上下文，调用后必须还原（防止线程复用串租户）")
    void appliesAndRestoresTenantContext() {
        TenantContext.clear();
        ToolCallback callback = findCallback(ToolNames.GET_SALES_ORDERS);
        String traceId = ToolCallRecorder.createTraceId();
        this.createdTraceIds.add(traceId);

        // 上下文里带租户；调用前线程上是干净的
        callback.call("{\"customerName\":\"任意客户\"}", contextOf(traceId));

        // 调用结束后必须还原成"干净"，而不是把 DEMO 留在线程上
        assertThat(TenantContext.getEntCode()).isNull();
        assertThat(TenantContext.getUserId()).isNull();
    }

    @Test
    @DisplayName("无租户时 Tool 调用失败（不静默查全部租户），但聚合器仍记下这次失败")
    void failsWhenTenantMissingFromContext() {
        ToolCallback callback = findCallback(ToolNames.GET_SALES_ORDERS);
        String traceId = ToolCallRecorder.createTraceId();
        this.createdTraceIds.add(traceId);

        // 上下文里刻意不带 entCode，且线程上也没有 → requireEntCode 抛异常
        assertThatThrownBy(() ->
                callback.call("{\"customerName\":\"x\"}", new ToolContext(Map.of(
                        ToolTraceKeys.TRACE_ID, traceId))))
                .hasMessageContaining("租户");

        // 聚合器是内存结构、不依赖租户，所以这次失败仍被记下——
        // 本轮的调用汇总与 M4「有没有产生业务数据」的判断都还能看到它。
        List<ToolCallRecord> records = this.recorder.getResults(traceId);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).status()).isEqualTo(LoggingToolCallback.STATUS_ERROR);
        assertThat(records.get(0).errorSummary()).contains("租户");

        // 但流水落不了库：写 tool_call_log 本身也要租户插件注入 ent_code，
        // 拿不到租户时必然失败，而 ToolCallLogService 只记 warn 不冒泡。
        // 这是本实现的已知边界——好在租户缺失在正常链路里不会发生。
        assertThat(logsOf(traceId)).isEmpty();
    }

    @Test
    @DisplayName("结果行数按返回数组长度推断")
    void countsResultRows() {
        ToolCallback callback = findCallback(ToolNames.GET_SALES_ORDERS);
        assertThat(callback).isInstanceOf(LoggingToolCallback.class);
        LoggingToolCallback logging = (LoggingToolCallback) callback;

        assertThat(logging.countResultRows("[{\"a\":1},{\"a\":2}]")).isEqualTo(2);
        assertThat(logging.countResultRows("[]")).isZero();
        // 非数组按 1 记：宁可记 1 也别记 0，否则会被误读成"没查到东西"
        assertThat(logging.countResultRows("{\"a\":1}")).isEqualTo(1);
        assertThat(logging.countResultRows("not json")).isEqualTo(1);
        assertThat(logging.countResultRows(null)).isZero();
    }

    @Test
    @DisplayName("流水写库失败只记 warn，绝不冒泡影响问答")
    void logWriteFailureIsSwallowed() {
        TenantContext.set(TENANT, 1L);

        ToolCallLog entry = new ToolCallLog();
        // 超长 tool_name 会让 INSERT 直接失败（varchar(64)）
        entry.setToolName("x".repeat(200));
        entry.setStatus(LoggingToolCallback.STATUS_SUCCESS);

        // 旁路数据写不进去不该抛异常
        assertThatCode(() -> this.logService.save(entry)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("clearTrace 后聚合记录消失（防止下一轮读到上一轮）")
    void clearTraceRemovesAggregation() {
        ToolCallback callback = findCallback(ToolNames.GET_SALES_ORDERS);
        String traceId = ToolCallRecorder.createTraceId();
        this.createdTraceIds.add(traceId);

        callback.call("{\"customerName\":\"x\"}", contextOf(traceId));
        assertThat(this.recorder.hasResults(traceId)).isTrue();

        this.recorder.clearTrace(traceId);
        assertThat(this.recorder.hasResults(traceId)).isFalse();
        assertThat(this.recorder.getResults(traceId)).isEmpty();
    }

    private ToolContext contextOf(String traceId) {
        return new ToolContext(Map.<String, Object>of(
                ToolTraceKeys.TRACE_ID, traceId,
                ToolTraceKeys.CONVERSATION_ID, "conv-" + traceId,
                ToolTraceKeys.ENT_CODE, TENANT,
                ToolTraceKeys.USER_ID, "1",
                ToolTraceKeys.MODE, "auto"));
    }

    /**
     * 查询某 traceId 的流水。
     * <p>
     * <b>必须先设租户上下文</b>：Tool 调用结束后包装器已把上下文还原为空，
     * 此时直接查会被租户插件拦住（这也反过来证明了还原逻辑确实生效）。
     */
    private List<ToolCallLog> logsOf(String traceId) {
        TenantContext.set(TENANT, 1L);
        try {
            return this.logMapper.selectByTrace(traceId);
        }
        finally {
            TenantContext.clear();
        }
    }

    private ToolCallback findCallback(String toolName) {
        ToolSnapshot snapshot = this.registry.snapshot();
        return snapshot.callbacks().stream()
                .filter(cb -> toolName.equals(cb.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("快照里找不到 Tool：" + toolName));
    }

    /** 删除某 traceId 的日志；租户插件会给 DELETE 加 ent_code，故必须先设上下文 */
    private void purgeLogs(String traceId) {
        TenantContext.set(TENANT, 1L);
        for (ToolCallLog log : this.logMapper.selectByTrace(traceId)) {
            this.logMapper.deleteById(log.getId());
        }
        TenantContext.clear();
    }

}
