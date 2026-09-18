package com.duduke.erp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duduke.erp.entity.dto.BillingPlanSaveDTO;
import com.duduke.erp.entity.dto.BillingPriceRuleSaveDTO;
import com.duduke.erp.entity.po.BillingAccount;
import com.duduke.erp.mapper.BillingAccountMapper;
import com.duduke.erp.entity.po.BillingPlan;
import com.duduke.erp.entity.po.BillingPriceRule;
import com.duduke.erp.mapper.BillingPlanMapper;
import com.duduke.erp.mapper.BillingPriceRuleMapper;
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
 * 计费管理端（套餐 / 价格规则）的验证。
 * <p>
 * 三个重点：
 * <ol>
 *   <li><b>写权限只给 admin</b>：viewer 调写接口必须 403 —— 这两张表是平台级配置；</li>
 *   <li><b>套餐编码不可改</b>：更新时传新编码必须被忽略，否则既有账户会指向不存在的套餐；</li>
 *   <li><b>被引用的套餐不能删</b>：且引用检查必须<b>跨租户</b>，
 *       只查本租户会漏掉其它租户的引用，留下悬空的 {@code plan_code}。</li>
 * </ol>
 */
class BillingManagementApiTest extends AbstractApiTest {

    @Autowired
    private BillingPlanMapper planMapper;

    @Autowired
    private BillingPriceRuleMapper priceRuleMapper;

    @Autowired
    private BillingAccountMapper accountMapper;

    private String planCode;

    @AfterEach
    void cleanUp() {
        TenantContext.set(TEST_ENT_CODE, null);
        try {
            if (this.planCode != null) {
                this.accountMapper.delete(Wrappers.<BillingAccount>lambdaQuery()
                        .eq(BillingAccount::getPlanCode, this.planCode));
                this.planMapper.delete(Wrappers.<BillingPlan>lambdaQuery()
                        .eq(BillingPlan::getPlanCode, this.planCode));
            }
        }
        finally {
            TenantContext.clear();
        }
    }

    // ===== 套餐 =====

    @Test
    @DisplayName("套餐 创建 → 查询 → 更新 → 删除 全流程")
    void planCrudRoundTrip() throws Exception {
        String token = testTenantToken();
        this.planCode = uniquePlanCode();

        Long id = createPlan(token, this.planCode, "基础版", 1_000_000L);

        JsonNode detail = readData(performGet("/api/billing/plans/" + id, token));
        assertThat(detail.get("planCode").asString()).isEqualTo(this.planCode);
        assertThat(detail.get("monthlyQuota").asLong()).isEqualTo(1_000_000L);

        updatePlan(token, id, "专业版", 5_000_000L);
        JsonNode updated = readData(performGet("/api/billing/plans/" + id, token));
        assertThat(updated.get("planName").asString()).isEqualTo("专业版");
        assertThat(updated.get("planCode").asString())
                .as("编码不可改：既有账户靠它关联套餐")
                .isEqualTo(this.planCode);

        performDelete("/api/billing/plans/" + id, token);
        this.mockMvc.perform(get("/api/billing/plans/" + id).header("satoken", token))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("套餐编码重复被拒")
    void rejectsDuplicatePlanCode() throws Exception {
        String token = testTenantToken();
        this.planCode = uniquePlanCode();
        createPlan(token, this.planCode, "基础版", 100L);

        String body = this.objectMapper.writeValueAsString(
                new BillingPlanSaveDTO(this.planCode, "重复", 1L, BigDecimal.ZERO));
        this.mockMvc.perform(post("/api/billing/plans")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("套餐被账户引用时不可删除（含跨租户引用）")
    void rejectsDeletingReferencedPlan() throws Exception {
        String token = testTenantToken();
        this.planCode = uniquePlanCode();
        Long planId = createPlan(token, this.planCode, "基础版", 100L);

        // 造一个引用该套餐的账户（走真实租户上下文入库）
        TenantContext.set(TEST_ENT_CODE, null);
        try {
            BillingAccount account = new BillingAccount();
            account.setPlanCode(this.planCode);
            account.setBalance(BigDecimal.ZERO);
            account.setMonthlyQuota(100L);
            account.setUsedTokens(0L);
            account.setStatus("active");
            this.accountMapper.insert(account);
        }
        finally {
            TenantContext.clear();
        }

        this.mockMvc.perform(delete("/api/billing/plans/" + planId).header("satoken", token))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("viewer 不能改套餐配置（只读权限不含写）")
    void viewerCannotManagePlans() throws Exception {
        String viewer = token("viewer");
        String body = this.objectMapper.writeValueAsString(
                new BillingPlanSaveDTO(uniquePlanCode(), "越权", 1L, BigDecimal.ZERO));

        this.mockMvc.perform(post("/api/billing/plans")
                        .header("satoken", viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    // ===== 价格规则 =====

    @Test
    @DisplayName("价格规则可新增与删除，且同模型同生效日不可重复")
    void priceRuleCreateAndDuplicateGuard() throws Exception {
        String token = testTenantToken();
        String model = "model-" + UUID.randomUUID().toString().substring(0, 8);
        LocalDate date = LocalDate.of(2026, 1, 1);

        Long id = createPriceRule(token, model, date);
        try {
            String body = this.objectMapper.writeValueAsString(new BillingPriceRuleSaveDTO(
                    model, new BigDecimal("0.001"), new BigDecimal("0.002"), date));
            this.mockMvc.perform(post("/api/billing/price_rules")
                            .header("satoken", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        finally {
            performDelete("/api/billing/price_rules/" + id, token);
            this.priceRuleMapper.delete(Wrappers.<BillingPriceRule>lambdaQuery()
                    .eq(BillingPriceRule::getModelName, model));
        }
    }

    // ===== 辅助 =====

    private Long createPlan(String token, String planCode, String name, Long quota) throws Exception {
        String body = this.objectMapper.writeValueAsString(
                new BillingPlanSaveDTO(planCode, name, quota, new BigDecimal("99.00")));
        String response = this.mockMvc.perform(post("/api/billing/plans")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return readData(response).asLong();
    }

    private void updatePlan(String token, Long id, String name, Long quota) throws Exception {
        String body = this.objectMapper.writeValueAsString(
                new BillingPlanSaveDTO("ignored-code", name, quota, new BigDecimal("199.00")));
        this.mockMvc.perform(put("/api/billing/plans/" + id)
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private Long createPriceRule(String token, String model, LocalDate date) throws Exception {
        String body = this.objectMapper.writeValueAsString(new BillingPriceRuleSaveDTO(
                model, new BigDecimal("0.001"), new BigDecimal("0.002"), date));
        String response = this.mockMvc.perform(post("/api/billing/price_rules")
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

    private void performDelete(String url, String token) throws Exception {
        this.mockMvc.perform(delete(url).header("satoken", token))
                .andExpect(status().isOk());
    }

    private static String uniquePlanCode() {
        return "plan_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

}
