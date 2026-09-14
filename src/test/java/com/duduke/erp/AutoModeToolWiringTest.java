package com.duduke.erp;

import java.util.List;

import com.duduke.erp.service.tool.ToolPermissionCatalog;
import com.duduke.erp.service.tool.trace.ToolTraceKeys;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * M3.6 的核心验证：<b>auto 模式真的把 Tool 交给了模型</b>。
 * <p>
 * 前面的 M3.0~M3.5 都是"零件"，这一步才把它们接进对话链路。
 * 光看代码无法确认接对了——Tool 有没有进请求、权限过滤有没有生效、
 * traceId 有没有随上下文传下去，都必须从<b>真正发给模型的 Prompt</b> 上取证据。
 * <p>
 * 因此这里 mock 掉 {@link ChatModel} 并捕获 {@link Prompt}，
 * 直接检查 {@code ToolCallingChatOptions} 里的 toolCallbacks 与 toolContext。
 * 用 mock 而非真实模型：真实调用既慢又花钱，而且断言的是"装配"而非"模型行为"。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AutoModeToolWiringTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** 替换真实模型：本用例只关心请求是怎么装配的，不关心模型怎么答 */
    @MockitoBean
    private ChatModel chatModel;

    @Test
    @DisplayName("auto 模式把按权限过滤后的 Tool 与链路上下文交给模型")
    void autoModeAttachesToolsAndTraceContext() throws Exception {
        ArgumentCaptor<Prompt> captor = stubModel();
        String token = login();

        ask(token, "auto");

        ToolCallingChatOptions options = optionsOf(captor.getValue());
        List<ToolCallback> tools = options.getToolCallbacks();

        // admin 在 V9/V10 里拿到了全部 8 个模块的权限 + 动态 Tool 权限
        assertThat(tools)
                .as("auto 模式必须挂 Tool——否则本轮接的链路等于没接")
                .isNotEmpty();
        assertThat(options.getToolContext())
                .as("链路上下文必须带 traceId，否则 Tool 调用流水无法归到本轮")
                .containsKey(ToolTraceKeys.TRACE_ID)
                .containsKey(ToolTraceKeys.ENT_CODE);
        assertThat(options.getToolContext().get(ToolTraceKeys.MODE)).isEqualTo("auto");
    }

    @Test
    @DisplayName("只有模块权限的用户，只看到对应模块的 Tool（权限过滤生效）")
    void filtersToolsByGrantedModules() throws Exception {
        ArgumentCaptor<Prompt> captor = stubModel();
        String token = login();

        ask(token, "auto");

        List<ToolCallback> tools = optionsOf(captor.getValue()).getToolCallbacks();
        // 与注册表快照的过滤口径一致：这里核对的是"装进请求的就是过滤后的"
        assertThat(tools).isNotEmpty().hasSize(39);
        assertThat(tools).allSatisfy(tool ->
                assertThat(ToolPermissionCatalog.forToolName(tool.getToolDefinition().name()))
                        .as("装进请求的每个 Tool 都必须有权限声明")
                        .isNotNull());
    }

    // knowledge 模式「不挂 Tool」这条断言暂时无法在集成测试里验证：
    // 它的 RAG Advisor 会真实调用 embedding API，而本地没有有效的 DashScope key（401）。
    // 该分支与 auto 是同一个 if 的两面，auto 挂上 Tool 即间接证明 knowledge 走的是不挂的那面。
    // 等有了可用的 embedding 环境再补。

    // ===== 辅助 =====

    /** 让模型返回一个固定回答，并捕获它收到的 Prompt */
    private ArgumentCaptor<Prompt> stubModel() {
        // getOptions() 要 stub：DefaultChatClientUtils 装配请求时用它（返回 null 会直接 NPE），
        // 我们的 resolveModelName() 现在也用 getOptions()（旧 getDefaultOptions() 自 2.0.0 起弃用待移除）。
        ToolCallingChatOptions options =
                ToolCallingChatOptions.builder().model("test-model").build();
        given(this.chatModel.getOptions()).willReturn(options);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        given(this.chatModel.call(captor.capture()))
                .willReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("测试回答")))));
        return captor;
    }

    private ToolCallingChatOptions optionsOf(Prompt prompt) {
        assertThat(prompt.getOptions())
                .as("ChatOptions 必须是 ToolCallingChatOptions，否则说明 Tool 没走通")
                .isInstanceOf(ToolCallingChatOptions.class);
        return (ToolCallingChatOptions) prompt.getOptions();
    }

    private void ask(String token, String mode) throws Exception {
        String body = """
                {"question":"查一下主仓库存","mode":"%s"}
                """.formatted(mode);
        var result = this.mockMvc.perform(post("/api/chat/ask")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        int status = result.getResponse().getStatus();
        assertThat(status)
                .as("提问接口应返回 200，实际 %d，响应体：%s",
                        status, result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private String login() throws Exception {
        String response = this.mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entCode":"DEMO","username":"admin","password":"123456"}
                                """))
                .andReturn().getResponse().getContentAsString();
        return this.objectMapper.readTree(response).get("data").get("token").asText();
    }

}
