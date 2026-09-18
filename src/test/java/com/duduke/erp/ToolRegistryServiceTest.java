package com.duduke.erp;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.UUID;

import com.duduke.erp.entity.po.LlmTool;
import com.duduke.erp.mapper.LlmToolMapper;
import com.duduke.erp.service.tool.ToolNames;
import com.duduke.erp.service.tool.ToolPermissionCatalog;
import com.duduke.erp.service.tool.ToolRegistryService;
import com.duduke.erp.service.tool.ToolSnapshot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tool 注册表验证。
 * <p>
 * 重点守三条不变量：
 * <ol>
 *   <li><b>权限映射覆盖完整</b>——每个 Tool 名常量都有对应权限码。
 *       漏一个的后果是「该 Tool 被拒绝注册」，用反射枚举核对最彻底；</li>
 *   <li><b>按权限过滤正确</b>——只有拿到对应权限码的用户才看得到该模块 Tool；</li>
 *   <li><b>fail-closed</b>——未声明权限或配置非法的 Tool 被跳过，而不是放行。</li>
 * </ol>
 *
 * <h3>为什么不能断言"快照总数 = 39"</h3>
 * {@code llm_tool} 是<b>全局配置表</b>（无 ent_code，也不在租户隔离范围内），
 * 而管理端允许真实创建动态 Tool——测试跑在同一个库上，快照里随时可能多出几条真实配置。
 * 断言总数等于"库里恰好没有别的动态 Tool"，等于把测试绑死在"别人没用这个功能"上。
 * <p>
 * 所以这里统一用 {@link #codeToolCount} 只数<b>代码 Tool</b>：
 * 动态 Tool 的权限码固定是 {@link ToolPermissionCatalog#DYNAMIC}，据此可把两者分开。
 * 「跳过类」用例则直接断言"那个 Tool 不在快照里"，比数总数更贴近意图。
 */
@SpringBootTest
class ToolRegistryServiceTest {

    @Autowired
    private ToolRegistryService registry;

    @Autowired
    private LlmToolMapper llmToolMapper;

    /** 本用例插入的动态 Tool 主键，用于精确清理——不能整表删，那会带走真实配置 */
    private final java.util.List<Long> createdIds = new java.util.ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long id : this.createdIds) {
            this.llmToolMapper.deleteById(id);
        }
        this.createdIds.clear();
        this.registry.refresh();
    }

    @Test
    @DisplayName("启动后快照里含全部 39 个代码 Tool")
    void registersAllCodeTools() {
        ToolSnapshot snapshot = this.registry.snapshot();

        assertThat(snapshot.version()).isPositive();
        assertThat(codeToolCount(snapshot))
                .as("代码 Tool 数量固定为 39；动态 Tool 另算，不参与这个断言")
                .isEqualTo(39);
    }

    @Test
    @DisplayName("权限映射覆盖 ToolNames 里的每个业务 Tool 名")
    void permissionCatalogCoversEveryToolName() throws Exception {
        for (Field field : ToolNames.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            String name = (String) field.get(null);
            // 系统保留名（内部 Tool）不在业务 Tool 目录里，跳过。
            // 用 isReserved 判断而不是写死某个常量：将来增删内部 Tool 都不用改这里。
            if (ToolNames.isReserved(name)) {
                continue;
            }
            assertThat(ToolPermissionCatalog.declaredToolNames())
                    .as("Tool %s 常量在 ToolPermissionCatalog 里没有声明权限，会被拒绝注册", name)
                    .contains(name);
        }
    }

    @Test
    @DisplayName("按权限过滤：只有对应模块权限的用户才看得到该模块 Tool")
    void filtersToolsByPermission() {
        ToolSnapshot snapshot = this.registry.snapshot();

        // 只给销售权限
        assertThat(namesOf(snapshot.visibleTo(Set.of(ToolPermissionCatalog.SALES))))
                .containsOnly(ToolNames.GET_SALES_ORDERS, ToolNames.GET_SALES_ORDER_DETAIL,
                        ToolNames.GET_SHIPMENT_STATUS, ToolNames.GET_ACCOUNTS_RECEIVABLE,
                        ToolNames.GET_RECENT_SALES_ORDERS, ToolNames.GET_SALES_SUMMARY);

        // 同时给销售与仓库（动态 Tool 不随模块权限放开，故这里仍是 11）
        assertThat(namesOf(snapshot.visibleTo(Set.of(
                ToolPermissionCatalog.SALES, ToolPermissionCatalog.WAREHOUSE))))
                .hasSize(11);

        // 一个权限都没有 → 什么都看不到（不是「全都看得到」）
        assertThat(snapshot.visibleTo(Set.of())).isEmpty();
    }

    @Test
    @DisplayName("数据库里的动态 Tool 会被注册，且要求独立权限码")
    void registersDynamicToolFromDatabase() {
        String name = "dyn_" + UUID.randomUUID().toString().substring(0, 8);
        LlmTool definition = dynamicTool(name);
        insertDynamicTool(definition);
        this.registry.refresh();

        ToolSnapshot snapshot = this.registry.snapshot();
        // 断言"我这个进了快照"，而不是快照总数——总数里还包含别人建的真实配置
        assertThat(snapshot.requiredPermissions()).containsEntry(name, ToolPermissionCatalog.DYNAMIC);
        assertThat(codeToolCount(snapshot)).isEqualTo(39);

        // 动态 Tool 不随模块权限放开
        assertThat(namesOf(snapshot.visibleTo(Set.of(ToolPermissionCatalog.SALES)))).doesNotContain(name);
        assertThat(namesOf(snapshot.visibleTo(Set.of(ToolPermissionCatalog.DYNAMIC)))).contains(name);
    }

    @Test
    @DisplayName("动态 Tool 与代码 Tool 重名时跳过动态那个（保留代码 Tool）")
    void skipsDynamicToolCollidingWithCodeToolName() {
        String name = ToolNames.GET_SALES_ORDERS;
        insertDynamicTool(dynamicTool(name));
        this.registry.refresh();

        ToolSnapshot snapshot = this.registry.snapshot();

        assertThat(codeToolCount(snapshot)).isEqualTo(39);
        assertThat(snapshot.requiredPermissions())
                .as("重名时保留代码 Tool 的权限映射，不能被动态 Tool 顶掉")
                .containsEntry(name, ToolPermissionCatalog.SALES);
    }

    @Test
    @DisplayName("配置非法的动态 Tool 被跳过（fail-closed）")
    void skipsInvalidDynamicTool() {
        String name = "dyn_" + UUID.randomUUID().toString().substring(0, 8);
        LlmTool invalid = dynamicTool(name);
        // 含分号，应被校验器拒绝
        invalid.setSqlTemplate("SELECT product_code FROM product; DROP TABLE product");
        insertDynamicTool(invalid);
        this.registry.refresh();

        // 直接断言"这一条没进去"，比"总数没变"更贴近意图，也不受库里其他动态 Tool 影响
        assertThat(this.registry.snapshot().requiredPermissions())
                .as("配置非法的动态 Tool 不应进入快照")
                .doesNotContainKey(name);
        assertThat(codeToolCount(this.registry.snapshot())).isEqualTo(39);
    }

    @Test
    @DisplayName("inactive 的动态 Tool 不注册")
    void ignoresInactiveDynamicTool() {
        String name = "dyn_" + UUID.randomUUID().toString().substring(0, 8);
        LlmTool inactive = dynamicTool(name);
        inactive.setStatus("inactive");
        insertDynamicTool(inactive);
        this.registry.refresh();

        assertThat(this.registry.snapshot().requiredPermissions())
                .as("停用的动态 Tool 不应进入快照")
                .doesNotContainKey(name);
    }

    @Test
    @DisplayName("每次刷新版本号递增，旧快照不受影响（刷新不打断进行中的请求）")
    void refreshIncrementsVersionAndKeepsOldSnapshotUsable() {
        ToolSnapshot before = this.registry.snapshot();
        this.registry.refresh();
        ToolSnapshot after = this.registry.snapshot();

        assertThat(after.version()).isGreaterThan(before.version());
        // 旧快照仍可正常使用——这正是不可变快照的价值
        assertThat(codeToolCount(before)).isEqualTo(39);
        assertThat(before.visibleTo(Set.of(ToolPermissionCatalog.SALES))).hasSize(6);
    }

    /**
     * 只数代码 Tool。
     * <p>
     * 动态 Tool 的权限码固定是 {@link ToolPermissionCatalog#DYNAMIC}，
     * 据此把「库里真实的动态配置」从计数里排除，断言才不会随别人的配置漂移。
     */
    private long codeToolCount(ToolSnapshot snapshot) {
        return snapshot.requiredPermissions().values().stream()
                .filter(permission -> !ToolPermissionCatalog.DYNAMIC.equals(permission))
                .count();
    }

    private java.util.List<String> namesOf(java.util.List<ToolCallback> callbacks) {
        return callbacks.stream().map(cb -> cb.getToolDefinition().name()).toList();
    }

    /** 插入动态 Tool 并记录主键，供 {@code @AfterEach} 精确清理 */
    private void insertDynamicTool(LlmTool definition) {
        this.llmToolMapper.insert(definition);
        this.createdIds.add(definition.getId());
    }

    private LlmTool dynamicTool(String name) {
        LlmTool tool = new LlmTool();
        tool.setToolName(name);
        tool.setToolDesc("测试用动态 Tool");
        tool.setInputSchema("{\"type\":\"object\",\"properties\":{}}");
        tool.setSqlTemplate("SELECT product_code FROM product ORDER BY id");
        tool.setResultLimit(10);
        tool.setStatus("active");
        return tool;
    }

}
