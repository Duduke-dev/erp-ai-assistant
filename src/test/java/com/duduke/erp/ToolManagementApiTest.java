package com.duduke.erp;

import java.util.UUID;

import com.duduke.erp.entity.dto.LlmToolSaveDTO;
import com.duduke.erp.service.tool.ToolRegistryService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M3.7 动态 Tool 管理端的验证。
 * <p>
 * 重点不是「接口能不能调通」，而是三条<b>不报错但会埋雷</b>的规则真的生效了：
 * <ol>
 *   <li>名称与内置 Tool 冲突必须当场拒绝——否则管理端显示保存成功、
 *       模型却永远看不到它（注册表保留代码 Tool、跳过动态那个）。</li>
 *   <li>保存后注册表快照必须真的更新——这条验证 {@code afterCommit} 刷新确实挂上了，
 *       若只在事务内刷新，回滚后就会加载从未提交的数据。</li>
 *   <li>写操作必须受 {@code tool:llm:save} 约束——动态 Tool 是任意单层 SELECT，
 *       比逐个审过的代码 Tool 风险高得多。</li>
 * </ol>
 * 用 {@code admin}（有 list/save）与 {@code viewer}（无 save）两个账号对照。
 */
class ToolManagementApiTest extends AbstractApiTest {

    /** 无命名参数的合法只读查询，避免引入模板参数与 Schema 的交叉校验噪声 */
    private static final String SQL =
            "SELECT product_code, product_name FROM product WHERE category = 'valve'";

    private static final String SCHEMA = """
            {"type":"object","properties":{}}
            """;

    @Autowired
    private ToolRegistryService toolRegistryService;

    @Test
    @DisplayName("创建 → 查询 → 更新 → 删除 全流程字段正确")
    void createGetUpdateDeleteRoundTrip() throws Exception {
        String token = token("admin");
        String name = uniqueName();

        Long id = createTool(token, name, "初始说明");
        try {
            JsonNode detail = getTool(token, id);
            assertThat(detail.get("toolName").asString()).isEqualTo(name);
            assertThat(detail.get("toolDesc").asString()).isEqualTo("初始说明");
            // 状态为空要归一化成 active：注册表只加载 active，空值会让这条变成哑弹
            assertThat(detail.get("status").asString()).isEqualTo("active");
            assertThat(detail.get("resultLimit").asInt()).isEqualTo(50);

            updateTool(token, id, name, "更新后的说明");
            assertThat(getTool(token, id).get("toolDesc").asString()).isEqualTo("更新后的说明");
        }
        finally {
            removeTool(token, id);
        }

        // 删除后再查应报 400（资源不存在）
        this.mockMvc.perform(get("/api/tool/llm_tools/" + id)
                        .header("satoken", token))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("名称与内置 Tool 冲突时拒绝保存（否则是保存成功却用不了的哑弹）")
    void rejectsNameCollidingWithCodeTool() throws Exception {
        String token = token("admin");
        // getSalesOrders 是销售模块的内置 Tool
        String body = this.objectMapper.writeValueAsString(
                new LlmToolSaveDTO("getSalesOrders", "与内置 Tool 重名", SCHEMA, SQL,
                        null, 50, "active", null));

        String response = this.mockMvc.perform(post("/api/tool/llm_tools")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("内置 Tool 冲突");
    }

    @Test
    @DisplayName("非法 SQL 拒绝保存（复用与装载时同一套校验器）")
    void rejectsIllegalSql() throws Exception {
        String token = token("admin");
        String body = this.objectMapper.writeValueAsString(
                new LlmToolSaveDTO(uniqueName(), "写操作应被拒", SCHEMA,
                        "DELETE FROM product", null, 50, "active", null));

        this.mockMvc.perform(post("/api/tool/llm_tools")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("同名 Tool 不得重复创建")
    void rejectsDuplicateName() throws Exception {
        String token = token("admin");
        String name = uniqueName();
        Long id = createTool(token, name, "第一条");
        try {
            createExpectingBadRequest(token, name, "第二条");
        }
        finally {
            removeTool(token, id);
        }
    }

    @Test
    @DisplayName("保存与删除后注册表快照同步更新（验证 afterCommit 刷新）")
    void registrySnapshotFollowsSaveAndDelete() throws Exception {
        String token = token("admin");
        String name = uniqueName();

        Long id = createTool(token, name, "刷新验证");
        try {
            assertThat(this.toolRegistryService.snapshot().requiredPermissions())
                    .as("保存提交后，新 Tool 必须出现在注册表快照里")
                    .containsKey(name);
        }
        finally {
            removeTool(token, id);
        }

        assertThat(this.toolRegistryService.snapshot().requiredPermissions())
                .as("删除提交后，快照里不应再残留该 Tool")
                .doesNotContainKey(name);
    }

    @Test
    @DisplayName("无 tool:llm:save 权限的用户不能写")
    void viewerCannotWrite() throws Exception {
        String viewer = token("viewer");
        String body = this.objectMapper.writeValueAsString(
                new LlmToolSaveDTO(uniqueName(), "越权写入", SCHEMA, SQL, null, 50, "active", null));

        // 无权限 → 403（同时反证 Sa-Token 拦截器已注册，注解鉴权不是静默失效）
        this.mockMvc.perform(post("/api/tool/llm_tools")
                        .header("satoken", viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("调用日志分页查询可用，且受分页参数约束")
    void callLogsArePageable() throws Exception {
        String token = token("admin");

        String response = this.mockMvc.perform(get("/api/tool/call_logs")
                        .header("satoken", token)
                        .param("pageNo", "1")
                        .param("pageSize", "5"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode page = readData(response);
        assertThat(page.get("records").size())
                .as("每页条数不得超过 pageSize")
                .isLessThanOrEqualTo(5);
    }

    // ===== 辅助 =====

    private Long createTool(String token, String name, String desc) throws Exception {
        String body = this.objectMapper.writeValueAsString(
                new LlmToolSaveDTO(name, desc, SCHEMA, SQL, null, 50, "active", null));
        String response = this.mockMvc.perform(post("/api/tool/llm_tools")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return readData(response).asLong();
    }

    private void createExpectingBadRequest(String token, String name, String desc) throws Exception {
        String body = this.objectMapper.writeValueAsString(
                new LlmToolSaveDTO(name, desc, SCHEMA, SQL, null, 50, "active", null));
        this.mockMvc.perform(post("/api/tool/llm_tools")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    private JsonNode getTool(String token, Long id) throws Exception {
        String response = this.mockMvc.perform(get("/api/tool/llm_tools/" + id)
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return readData(response);
    }

    private void updateTool(String token, Long id, String name, String desc) throws Exception {
        String body = this.objectMapper.writeValueAsString(
                new LlmToolSaveDTO(name, desc, SCHEMA, SQL, null, 50, "active", null));
        this.mockMvc.perform(put("/api/tool/llm_tools/" + id)
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private void removeTool(String token, Long id) throws Exception {
        this.mockMvc.perform(delete("/api/tool/llm_tools/" + id)
                        .header("satoken", token))
                .andExpect(status().isOk());
    }

    /**
     * 名称须符合 {@code SqlToolValidator} 的字符规则，UUID 里的连字符不合法，先去掉。
     */
    private static String uniqueName() {
        return "test_tool_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

}
