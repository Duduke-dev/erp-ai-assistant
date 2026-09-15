package com.duduke.erp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 计费查询接口的验证。
 * <p>
 * 关注三点：权限闸门生效（无 token 与越权路径）、区间参数被正确接收、
 * 以及「起始晚于截止」这类参数错误返回 400 而不是 500。
 * <p>
 * 不构造真实的用量数据：本切片只做只读查询，
 * 数据由后续的「用量采集」切片写入——那部分再补写入侧的用例。
 */
class BillingApiTest extends AbstractApiTest {

    @Test
    @DisplayName("日用量查询返回数组（无数据时为空数组，不是 null）")
    void dailyUsageReturnsArray() throws Exception {
        String token = token("admin");

        String response = this.mockMvc.perform(get("/api/billing/usage/daily")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = readData(response);
        assertThat(data.isArray())
                .as("空结果也必须是数组，返回 null 会让前端多一层判空分支")
                .isTrue();
    }

    @Test
    @DisplayName("月用量查询可按账期区间过滤")
    void monthlyUsageFiltersByPeriod() throws Exception {
        String token = token("admin");

        String response = this.mockMvc.perform(get("/api/billing/usage/monthly")
                        .header("satoken", token)
                        .param("from", "2026-01")
                        .param("to", "2026-12"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(readData(response).isArray()).isTrue();
    }

    @Test
    @DisplayName("账户查询可用；未开户时 data 为 null")
    void accountEndpointAccessible() throws Exception {
        String token = token("admin");

        String response = this.mockMvc.perform(get("/api/billing/account")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 未开户时为 null，已开户时是对象——两种都接受，这里只断言接口通、结构可解析
        JsonNode data = readData(response);
        assertThat(data == null || data.isNull() || data.isObject()).isTrue();
    }

    @Test
    @DisplayName("起始晚于截止返回 400，而不是 500")
    void rejectsInvertedDateRange() throws Exception {
        String token = token("admin");

        this.mockMvc.perform(get("/api/billing/usage/daily")
                        .header("satoken", token)
                        .param("from", "2026-09-30")
                        .param("to", "2026-09-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("无 token 不能访问计费数据")
    void requiresLogin() throws Exception {
        this.mockMvc.perform(get("/api/billing/account"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("viewer 也能查看本租户用量（只读权限已授予）")
    void viewerCanReadUsage() throws Exception {
        String token = token("viewer");

        this.mockMvc.perform(get("/api/billing/usage/daily")
                        .header("satoken", token))
                .andExpect(status().isOk());
    }

}
