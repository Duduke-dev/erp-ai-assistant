package com.duduke.erp;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duduke.erp.entity.po.SysRole;
import com.duduke.erp.entity.po.SysUser;
import com.duduke.erp.entity.po.SysUserRole;
import com.duduke.erp.entity.po.Tenant;
import com.duduke.erp.mapper.SysRoleMapper;
import com.duduke.erp.mapper.SysUserMapper;
import com.duduke.erp.mapper.SysUserRoleMapper;
import com.duduke.erp.mapper.TenantMapper;
import com.duduke.erp.tenant.TenantContext;

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
 *
 * <h3>为什么需要一个专属测试租户</h3>
 * 有些用例依赖「该租户下没有账户 / 队列为空」这类**空环境前提**，
 * 而演示租户 {@code DEMO} 是会被真实使用的。一旦如此，测试只有两种下场：
 * 失败（唯一约束冲突），或者更糟——**清理逻辑把真实数据一起删掉**（已实际发生过：
 * 计费测试按 {@code type='recharge'} 全删，删掉了真实充值流水）。
 * <p>
 * 所以测试不该借用演示租户，而应拥有一块只属于自己的数据区：
 * 用 {@link #testTenantToken()} 代替 {@link #token(String)}，
 * 用 {@link #TEST_ENT_CODE} 代替字面量 {@code "DEMO"}。
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class AbstractApiTest {

    /**
     * 集成测试专用租户编码。与演示数据完全隔离，因此可以放心假设"这里面没有别人的数据"。
     */
    protected static final String TEST_ENT_CODE = "TEST_IT";

    /**
     * 演示账号的密码散列（明文 {@code 123456}）。
     * 测试租户的账号直接复用它，省去在测试里生成散列——散列算法或强度若调整，
     * 这里不需要跟着改。
     */
    private static final String DEMO_PASSWORD_HASH =
            "$2a$10$PiVbdn8ByQVD3oFgHvcdpOBjBglWKGgCBh2lXqIXEHQWIr9zXS9Ve";

    /** 演示租户编码，仅用于复制其权限清单 */
    private static final String DEMO_ENT_CODE = "DEMO";

    private static final String ADMIN_ROLE_CODE = "admin";

    private static final String TEST_USERNAME = "admin";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private SysRoleMapper sysRoleMapper;

    /**
     * 登录并返回 satoken，同时断言登录本身成功。
     *
     * @param entCode  租户编码；演示数据用 {@code DEMO}
     * @param username 用户名
     */
    protected String token(String entCode, String username) throws Exception {
        String body = """
                {"entCode":"%s","username":"%s","password":"123456"}
                """.formatted(entCode, username);
        String response = this.mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return this.objectMapper.readTree(response).get("data").get("token").asText();
    }

    /**
     * 登录演示租户（{@code DEMO}）。
     * <p>
     * 若用例依赖"空环境前提"（唯一约束、清空队列、按类型全删等），请改用
     * {@link #testTenantToken()}——否则一旦演示租户被真实使用，用例就会失败或误删数据。
     */
    protected String token(String username) throws Exception {
        return token(DEMO_ENT_CODE, username);
    }

    /**
     * 取得<b>测试租户</b>的管理员 token（必要时幂等地建好租户、角色与账号）。
     * <p>
     * 权限清单从演示租户的 admin 角色**复制**而非硬编码：新增权限码时测试自动跟随，
     * 不会因为"忘了同步权限列表"而出现 403。
     */
    protected String testTenantToken() throws Exception {
        ensureTestTenant();
        return token(TEST_ENT_CODE, TEST_USERNAME);
    }

    /** 取响应体 data 节点，便于断言业务字段 */
    protected JsonNode readData(String json) throws Exception {
        return this.objectMapper.readTree(json).get("data");
    }

    private void ensureTestTenant() {
        ensureTenantRow();

        // 先在演示租户上下文里取权限清单，再切到测试租户写数据（上下文不能嵌套）
        String permissions = demoAdminPermissions();

        TenantContext.set(TEST_ENT_CODE, null);
        try {
            ensureAdminRole(permissions);
            ensureAdminUser();
        }
        finally {
            TenantContext.clear();
        }
    }

    private void ensureTenantRow() {
        // tenant 是全局表（已列入 app.tenant.ignore-tables），不受租户插件约束
        boolean exists = this.tenantMapper.selectCount(Wrappers.<Tenant>lambdaQuery()
                .eq(Tenant::getEntCode, TEST_ENT_CODE)) > 0;
        if (exists) {
            return;
        }
        Tenant tenant = new Tenant();
        tenant.setEntCode(TEST_ENT_CODE);
        tenant.setEntName("集成测试租户");
        tenant.setStatus("active");
        this.tenantMapper.insert(tenant);
    }

    private String demoAdminPermissions() {
        TenantContext.set(DEMO_ENT_CODE, null);
        try {
            SysRole admin = this.sysRoleMapper.selectOne(Wrappers.<SysRole>lambdaQuery()
                    .eq(SysRole::getRoleCode, ADMIN_ROLE_CODE));
            return admin == null ? "" : admin.getPermissions();
        }
        finally {
            TenantContext.clear();
        }
    }

    private void ensureAdminRole(String permissions) {
        SysRole existing = this.sysRoleMapper.selectOne(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getRoleCode, ADMIN_ROLE_CODE));
        if (existing != null) {
            return;
        }
        SysRole role = new SysRole();
        role.setRoleCode(ADMIN_ROLE_CODE);
        role.setRoleName("集成测试管理员");
        role.setPermissions(permissions);
        this.sysRoleMapper.insert(role);
    }

    private void ensureAdminUser() {
        SysUser user = this.sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, TEST_USERNAME));
        if (user == null) {
            SysUser created = new SysUser();
            created.setUsername(TEST_USERNAME);
            created.setPasswordHash(DEMO_PASSWORD_HASH);
            created.setRealName("集成测试管理员");
            created.setStatus("active");
            this.sysUserMapper.insert(created);
            user = created;
        }

        boolean linked = this.sysUserRoleMapper.selectCount(Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getUserId, user.getId())) > 0;
        if (linked) {
            return;
        }
        SysRole role = this.sysRoleMapper.selectOne(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getRoleCode, ADMIN_ROLE_CODE));
        SysUserRole link = new SysUserRole();
        link.setUserId(user.getId());
        link.setRoleId(role.getId());
        this.sysUserRoleMapper.insert(link);
    }

}
