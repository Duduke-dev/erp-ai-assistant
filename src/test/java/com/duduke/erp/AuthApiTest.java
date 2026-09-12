package com.duduke.erp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证与鉴权链路回归测试。
 * <p>
 * 其中「权限不足返回 403」是最有价值的一条：Sa-Token 的注解鉴权依赖 SaInterceptor 驱动，
 * 一旦拦截器注册类没被扫描到，注解会被静默忽略（无权限也放行且不报错），
 * 只有越权用例能发现。
 */
class AuthApiTest extends AbstractApiTest {

    @Test
    @DisplayName("登录成功并返回 token")
    void loginReturnsToken() throws Exception {
        assertThat(token("admin")).isNotBlank();
    }

    @Test
    @DisplayName("统一响应包装固定为 code/message/data 三字段")
    void responseIsWrappedInThreeFields() throws Exception {
        this.mockMvc.perform(get("/api/auth/current").header("satoken", token("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.data.entCode").value("DEMO"));
    }

    @Test
    @DisplayName("无 token 访问受保护接口返回 401")
    void withoutTokenReturns401() throws Exception {
        this.mockMvc.perform(get("/api/auth/current"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("权限不足返回 403——证明 Sa-Token 拦截器确实生效")
    void insufficientPermissionReturns403() throws Exception {
        // viewer 只有 biz:sales:list，不含 biz:product:list
        this.mockMvc.perform(get("/api/auth/permission-probe").header("satoken", token("viewer")))
                .andExpect(status().isForbidden());

        this.mockMvc.perform(get("/api/auth/permission-probe").header("satoken", token("admin")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("伪造 X-Ent-Code 请求头不能改变当前租户")
    void spoofedTenantHeaderIgnored() throws Exception {
        this.mockMvc.perform(get("/api/auth/current")
                        .header("satoken", token("admin"))
                        .header("X-Ent-Code", "HACK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entCode").value("DEMO"));
    }

    @Test
    @DisplayName("密码错误返回 400")
    void wrongPasswordReturns400() throws Exception {
        this.mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"entCode":"DEMO","username":"admin","password":"wrong"}"""))
                .andExpect(status().isBadRequest());
    }

}
