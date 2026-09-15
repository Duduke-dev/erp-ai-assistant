package com.duduke.erp;

import java.util.UUID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duduke.erp.entity.dto.UserSaveDTO;
import com.duduke.erp.entity.po.SysUser;
import com.duduke.erp.entity.po.SysUserRole;
import com.duduke.erp.mapper.SysUserMapper;
import com.duduke.erp.mapper.SysUserRoleMapper;
import com.duduke.erp.tenant.TenantContext;

import org.junit.jupiter.api.AfterEach;
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
 * 本租户用户管理的验证。
 * <p>
 * 四个重点：
 * <ol>
 *   <li><b>密码散列不出网</b>：响应里出现 {@code passwordHash} 就等于给离线爆破送目标；</li>
 *   <li><b>不能删除自己</b>：管理员删掉自己就再也进不来，且没有自助恢复路径；</li>
 *   <li><b>角色必须存在于本租户</b>：否则用户挂着一个不存在的角色，权限判定会静默失效；</li>
 *   <li><b>写权限仅 admin</b>：分配角色等于授予权限。</li>
 * </ol>
 */
class UserManagementApiTest extends AbstractApiTest {

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private SysUserRoleMapper userRoleMapper;

    private String username;

    @AfterEach
    void cleanUp() {
        if (this.username == null) {
            return;
        }
        TenantContext.set("DEMO", 1L);
        try {
            SysUser user = this.userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                    .eq(SysUser::getUsername, this.username).last("LIMIT 1"));
            if (user != null) {
                this.userRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery()
                        .eq(SysUserRole::getUserId, user.getId()));
                this.userMapper.deleteById(user.getId());
            }
        }
        finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("新建用户 → 查询 → 更新：响应不含密码散列，角色正确")
    void createGetUpdateWithoutPasswordLeak() throws Exception {
        String token = token("admin");
        this.username = uniqueUsername();

        Long id = createUser(token, this.username, "viewer");

        String detailJson = performGet("/api/users/" + id, token);
        JsonNode detail = readData(detailJson);
        assertThat(detail.get("username").asString()).isEqualTo(this.username);
        assertThat(detail.get("roleCode").asString()).isEqualTo("viewer");
        assertThat(detailJson)
                .as("密码散列绝不能出现在响应里")
                .doesNotContain("passwordHash")
                .doesNotContain("password");

        // 更新姓名与状态（不改密码）
        String body = this.objectMapper.writeValueAsString(
                new UserSaveDTO(null, null, "改名后", "13800000000", "viewer", "disabled"));
        this.mockMvc.perform(put("/api/users/" + id)
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        JsonNode updated = readData(performGet("/api/users/" + id, token));
        assertThat(updated.get("realName").asString()).isEqualTo("改名后");
        assertThat(updated.get("status").asString()).isEqualTo("disabled");
    }

    @Test
    @DisplayName("用户名重复被拒")
    void rejectsDuplicateUsername() throws Exception {
        String token = token("admin");
        this.username = uniqueUsername();
        createUser(token, this.username, "viewer");

        String body = this.objectMapper.writeValueAsString(
                new UserSaveDTO(this.username, "123456", "重复", null, "viewer", "active"));
        this.mockMvc.perform(post("/api/users")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("角色不存在时拒绝建用户（避免挂空角色导致权限静默失效）")
    void rejectsUnknownRole() throws Exception {
        String token = token("admin");

        String body = this.objectMapper.writeValueAsString(new UserSaveDTO(
                uniqueUsername(), "123456", "无角色", null, "no_such_role", "active"));
        this.mockMvc.perform(post("/api/users")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("不能删除当前登录用户（否则自己再也进不来）")
    void cannotDeleteSelf() throws Exception {
        String token = token("admin");

        TenantContext.set("DEMO", null);
        Long adminId;
        try {
            adminId = this.userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                    .eq(SysUser::getUsername, "admin").last("LIMIT 1")).getId();
        }
        finally {
            TenantContext.clear();
        }

        this.mockMvc.perform(delete("/api/users/" + adminId).header("satoken", token))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("viewer 不能管理用户（分配角色等于授予权限）")
    void viewerCannotManageUsers() throws Exception {
        String viewer = token("viewer");
        String body = this.objectMapper.writeValueAsString(new UserSaveDTO(
                uniqueUsername(), "123456", "越权", null, "viewer", "active"));

        this.mockMvc.perform(post("/api/users")
                        .header("satoken", viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        this.mockMvc.perform(get("/api/users").header("satoken", viewer))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("删除他人用户成功，且角色关联一并清理")
    void deleteOtherUserCleansRoleLink() throws Exception {
        String token = token("admin");
        this.username = uniqueUsername();
        Long id = createUser(token, this.username, "viewer");

        this.mockMvc.perform(delete("/api/users/" + id).header("satoken", token))
                .andExpect(status().isOk());
        this.mockMvc.perform(get("/api/users/" + id).header("satoken", token))
                .andExpect(status().isBadRequest());

        TenantContext.set("DEMO", 1L);
        try {
            assertThat(this.userRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                    .eq(SysUserRole::getUserId, id)))
                    .as("用户删除后不应残留角色关联，否则重建同 id 时权限会莫名其妙地出现")
                    .isEmpty();
        }
        finally {
            TenantContext.clear();
        }
    }

    // ===== 辅助 =====

    private Long createUser(String token, String name, String roleCode) throws Exception {
        String body = this.objectMapper.writeValueAsString(
                new UserSaveDTO(name, "123456", "测试用户", null, roleCode, "active"));
        String response = this.mockMvc.perform(post("/api/users")
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

    private static String uniqueUsername() {
        return "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

}
