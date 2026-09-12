package com.duduke.erp;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 集成测试基类。
 * <p>
 * 使用完整 Spring 上下文：租户插件、Flyway、Sa-Token 拦截器都在链路内，
 * 任一环节失效都会在测试中暴露；切片测试恰恰测不到这类集成问题。
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class AbstractApiTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    /**
     * 登录并返回 satoken，同时断言登录本身成功。
     */
    protected String token(String username) throws Exception {
        String body = """
                {"entCode":"DEMO","username":"%s","password":"123456"}
                """.formatted(username);
        String response = this.mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return this.objectMapper.readTree(response).get("data").get("token").asText();
    }

    /** 取响应体 data 节点，便于断言业务字段 */
    protected JsonNode readData(String json) throws Exception {
        return this.objectMapper.readTree(json).get("data");
    }

}
