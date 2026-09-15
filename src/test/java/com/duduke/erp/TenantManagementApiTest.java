package com.duduke.erp;

import java.util.UUID;

import com.duduke.erp.entity.dto.TenantSaveDTO;
import com.duduke.erp.mapper.TenantMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 租户管理（平台视角）的验证。
 * <p>
 * 三个重点：
 * <ol>
 *   <li><b>跨租户可见性受控</b>：viewer 连列表都不能读——
 *       能列出所有租户等于看到平台全貌，不该随普通账号扩散；</li>
 *   <li><b>编码不可改</b>：编码是隔离键，改掉会让该租户既有数据全部「找不到」且不报错；</li>
 *   <li><b>停用用 status 表达，不做物理删除</b>：租户下有数据与账目，删了会成为孤儿。</li>
 * </ol>
 */
class TenantManagementApiTest extends AbstractApiTest {

    @Autowired
    private TenantMapper tenantMapper;

    private String entCode;

    @AfterEach
    void cleanUp() {
        if (this.entCode != null) {
            this.tenantMapper.delete(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.duduke.erp.entity.po.Tenant>
                                    lambdaQuery()
                            .eq(com.duduke.erp.entity.po.Tenant::getEntCode, this.entCode));
        }
    }

    @Test
    @DisplayName("租户 创建 → 查询 → 更新（停用）全流程，且编码不可改")
    void tenantCrudRoundTrip() throws Exception {
        String token = token("admin");
        this.entCode = uniqueEntCode();

        Long id = createTenant(token, this.entCode, "测试租户");

        JsonNode detail = readData(performGet("/api/platform/tenants/" + id, token));
        assertThat(detail.get("entCode").asString()).isEqualTo(this.entCode);
        assertThat(detail.get("status").asString()).isEqualTo("active");

        // 更新：改名 + 停用，同时故意传一个新编码
        String body = this.objectMapper.writeValueAsString(
                new TenantSaveDTO("hacked_code", "改名后的租户", "disabled"));
        this.mockMvc.perform(put("/api/platform/tenants/" + id)
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        JsonNode updated = readData(performGet("/api/platform/tenants/" + id, token));
        assertThat(updated.get("entName").asString()).isEqualTo("改名后的租户");
        assertThat(updated.get("status").asString()).isEqualTo("disabled");
        assertThat(updated.get("entCode").asString())
                .as("编码是隔离键，更新时必须忽略传入的新编码")
                .isEqualTo(this.entCode);
    }

    @Test
    @DisplayName("租户编码重复被拒")
    void rejectsDuplicateEntCode() throws Exception {
        String token = token("admin");
        this.entCode = uniqueEntCode();
        createTenant(token, this.entCode, "测试租户");

        String body = this.objectMapper.writeValueAsString(
                new TenantSaveDTO(this.entCode, "重复", "active"));
        this.mockMvc.perform(post("/api/platform/tenants")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("viewer 不能读租户列表（跨租户可见性不扩散）")
    void viewerCannotListTenants() throws Exception {
        String viewer = token("viewer");

        this.mockMvc.perform(get("/api/platform/tenants").header("satoken", viewer))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("viewer 不能建租户")
    void viewerCannotCreateTenant() throws Exception {
        String viewer = token("viewer");
        String body = this.objectMapper.writeValueAsString(
                new TenantSaveDTO(uniqueEntCode(), "越权", "active"));

        this.mockMvc.perform(post("/api/platform/tenants")
                        .header("satoken", viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    // ===== 辅助 =====

    private Long createTenant(String token, String code, String name) throws Exception {
        String body = this.objectMapper.writeValueAsString(
                new TenantSaveDTO(code, name, "active"));
        String response = this.mockMvc.perform(post("/api/platform/tenants")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return readData(response).asLong();
    }

    private String performGet(String url, String token) throws Exception {
        return this.mockMvc.perform(get(url).header("satoken", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static String uniqueEntCode() {
        return "ENT" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

}
