package com.duduke.erp;

import java.util.List;
import java.util.UUID;

import com.duduke.erp.entity.po.LlmTool;
import com.duduke.erp.entity.po.ToolCallLog;
import com.duduke.erp.mapper.LlmToolMapper;
import com.duduke.erp.mapper.ToolCallLogMapper;
import com.duduke.erp.tenant.TenantContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3.0 数据访问验证。
 * <p>
 * 验证三件事，它们都是「不报错但功能没了」的高危项：
 * <ol>
 *   <li>{@code llm_tool} 确实在 {@code ignore-tables} 里 —— 否则租户插件会给它加
 *       {@code ent_code} 条件，而该表没有这一列，句子直接报错；</li>
 *   <li>{@code tool_call_log} 的 jsonb 列能正确往返 —— 漏了 {@code autoResultMap = true}
 *       时不会报错，只会读出 null；</li>
 *   <li>{@code tool_call_log} 真的按租户隔离 —— 跨租户泄漏是多租户系统里最严重的缺陷，
 *       而且它不报错。</li>
 * </ol>
 * 数据用 UUID 后缀，{@code finally} 清理。
 */
@SpringBootTest
class ToolDataAccessTest {

    private static final String TENANT = "DEMO";

    private static final String OTHER_TENANT = "OTHER";

    @Autowired
    private LlmToolMapper llmToolMapper;

    @Autowired
    private ToolCallLogMapper toolCallLogMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void clearTenantContext() {
        // Tomcat / 测试线程复用，不清理会让下一个用例读到本轮的租户
        TenantContext.clear();
    }

    @Test
    @DisplayName("llm_tool 完整往返：长文本 schema 与 SQL 模板不丢字段")
    void llmToolRoundTrip() {
        TenantContext.set(TENANT, 1L);
        LlmTool tool = newLlmTool("t_roundtrip_");
        try {
            this.llmToolMapper.insert(tool);

            LlmTool loaded = this.llmToolMapper.selectByToolName(tool.getToolName());
            assertThat(loaded).isNotNull();
            assertThat(loaded.getId()).isEqualTo(tool.getId());
            assertThat(loaded.getToolDesc()).isEqualTo(tool.getToolDesc());
            assertThat(loaded.getInputSchema()).isEqualTo(tool.getInputSchema());
            assertThat(loaded.getSqlTemplate()).isEqualTo(tool.getSqlTemplate());
            assertThat(loaded.getTableAlias()).isEqualTo("product");
            assertThat(loaded.getResultLimit()).isEqualTo(50);
            assertThat(loaded.getStatus()).isEqualTo("active");
        }
        finally {
            if (tool.getId() != null) {
                this.llmToolMapper.deleteById(tool.getId());
            }
        }
    }

    @Test
    @DisplayName("llm_tool 是全局表：无租户上下文也能读写（证明已在 ignore-tables 中）")
    void llmToolIsNotTenantScoped() {
        // 刻意不设租户。若 llm_tool 未进 ignore-tables，插件会调 requireEntCode()
        // 抛 IllegalStateException —— 这个用例就会响亮地失败。
        TenantContext.clear();
        LlmTool tool = newLlmTool("t_global_");
        try {
            this.llmToolMapper.insert(tool);
            assertThat(this.llmToolMapper.selectByToolName(tool.getToolName())).isNotNull();
            assertThat(this.llmToolMapper.selectActiveTools())
                    .extracting(LlmTool::getToolName)
                    .contains(tool.getToolName());
        }
        finally {
            if (tool.getId() != null) {
                this.llmToolMapper.deleteById(tool.getId());
            }
        }
    }

    @Test
    @DisplayName("tool_call_log 往返：V8 新增的 user_id / mode 与 jsonb 参数都正确落库")
    void toolCallLogRoundTrip() {
        TenantContext.set(TENANT, 1L);
        String conversationId = "conv-" + UUID.randomUUID();
        String arguments = "{\"customerName\":\"张三\",\"limit\":5}";

        ToolCallLog row = new ToolCallLog();
        row.setConversationId(conversationId);
        row.setTraceId("trace-" + UUID.randomUUID());
        row.setToolName("getSalesOrders");
        row.setToolSource("code");
        row.setModelName("qwen-plus");
        row.setUserId(1L);
        row.setMode("auto");
        row.setArguments(arguments);
        row.setStatus("success");
        row.setElapsedMs(12L);
        row.setResultCount(3);

        try {
            this.toolCallLogMapper.insert(row);

            List<ToolCallLog> loaded = this.toolCallLogMapper.selectByConversation(conversationId);
            assertThat(loaded).hasSize(1);
            ToolCallLog actual = loaded.get(0);
            assertThat(actual.getUserId()).isEqualTo(1L);
            assertThat(actual.getMode()).isEqualTo("auto");
            // jsonb 不保留键顺序与空白：存进去的 {"a":1,"b":2} 取出来可能是 {"b": 2, "a": 1}。
            // 所以必须按 JSON 语义比较，不能按字符串精确比较——
            // 任何对 jsonb 取出的字符串做 equals 的代码（去重、缓存键）都会因此失效。
            assertThat(this.objectMapper.readTree(actual.getArguments()))
                    .as("jsonb 必须语义等价往返；@TableName 漏了 autoResultMap 时这里会是 null")
                    .isEqualTo(this.objectMapper.readTree(arguments));
            assertThat(actual.getEntCode())
                    .as("ent_code 应由租户插件自动填充，业务代码不写")
                    .isEqualTo(TENANT);
        }
        finally {
            if (row.getId() != null) {
                this.toolCallLogMapper.deleteById(row.getId());
            }
        }
    }

    @Test
    @DisplayName("tool_call_log 按租户隔离：换租户后查不到（证明插件自动注入有效）")
    void toolCallLogIsTenantScoped() {
        TenantContext.set(TENANT, 1L);
        String conversationId = "conv-" + UUID.randomUUID();

        ToolCallLog row = new ToolCallLog();
        row.setConversationId(conversationId);
        row.setToolName("getInventory");
        row.setToolSource("code");
        row.setStatus("success");

        try {
            this.toolCallLogMapper.insert(row);
            assertThat(this.toolCallLogMapper.selectByConversation(conversationId)).hasSize(1);

            TenantContext.set(OTHER_TENANT, 2L);
            assertThat(this.toolCallLogMapper.selectByConversation(conversationId))
                    .as("换租户后必须查不到——查得到就是跨租户泄漏")
                    .isEmpty();
        }
        finally {
            TenantContext.set(TENANT, 1L);
            if (row.getId() != null) {
                this.toolCallLogMapper.deleteById(row.getId());
            }
        }
    }

    /** 造一条结构完整的 Tool 定义，name 带 UUID 后缀避免与既有数据冲突 */
    private LlmTool newLlmTool(String namePrefix) {
        LlmTool tool = new LlmTool();
        tool.setToolName(namePrefix + UUID.randomUUID().toString().substring(0, 8));
        tool.setToolDesc("测试用动态 Tool：按产品编码查库存");
        tool.setInputSchema("{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\"}},"
                + "\"required\":[\"code\"],\"additionalProperties\":false}");
        tool.setSqlTemplate("SELECT code, name FROM product WHERE code = :code");
        tool.setTableAlias("product");
        tool.setResultLimit(50);
        tool.setStatus("active");
        tool.setRemark("测试数据，用例结束时删除");
        return tool;
    }

}
