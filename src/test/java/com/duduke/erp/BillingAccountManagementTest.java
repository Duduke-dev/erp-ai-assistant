package com.duduke.erp;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duduke.erp.entity.dto.BillingAccountSaveDTO;
import com.duduke.erp.entity.dto.BillingPlanSaveDTO;
import com.duduke.erp.entity.dto.BillingRechargeDTO;
import com.duduke.erp.entity.po.BillingAccount;
import com.duduke.erp.entity.po.BillingInvoice;
import com.duduke.erp.entity.po.BillingPlan;
import com.duduke.erp.entity.po.BillingTransaction;
import com.duduke.erp.mapper.BillingAccountMapper;
import com.duduke.erp.mapper.BillingInvoiceMapper;
import com.duduke.erp.mapper.BillingPlanMapper;
import com.duduke.erp.mapper.BillingTransactionMapper;
import com.duduke.erp.tenant.TenantContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 计费管理端 4b：账户 / 交易 / 发票。
 * <p>
 * <b>注意</b>：本用例会在演示租户下建账户，而账户表对 {@code ent_code} 有唯一约束。
 * 若清理失败，其它走 /api/chat 的用例会受影响（配额校验会读到这个账户）。
 * 因此清理放在 {@code finally}，且按本用例专属的 planCode 精确定位。
 */
class BillingAccountManagementTest extends AbstractApiTest {

    @Autowired
    private BillingAccountMapper accountMapper;

    @Autowired
    private BillingTransactionMapper transactionMapper;

    @Autowired
    private BillingInvoiceMapper invoiceMapper;

    @Autowired
    private BillingPlanMapper planMapper;

    private String planCode;

    @AfterEach
    void cleanUp() {
        TenantContext.set("DEMO", 1L);
        try {
            if (this.planCode == null) {
                return;
            }
            // billing_transaction 没有 account_id 列（表结构如此），
            // 因此按类型清理：本用例是唯一造 recharge 流水的地方
            this.transactionMapper.delete(Wrappers.<BillingTransaction>lambdaQuery()
                    .eq(BillingTransaction::getType, "recharge"));
            this.accountMapper.delete(Wrappers.<BillingAccount>lambdaQuery()
                    .eq(BillingAccount::getPlanCode, this.planCode));
            this.planMapper.delete(Wrappers.<BillingPlan>lambdaQuery()
                    .eq(BillingPlan::getPlanCode, this.planCode));
            this.invoiceMapper.delete(Wrappers.<BillingInvoice>lambdaQuery()
                    .eq(BillingInvoice::getPeriod, "2099-12"));
        }
        finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("开户 → 充值 → 查流水：流水金额为正且余额正确")
    void accountLifecycleAndRecharge() throws Exception {
        String token = token("admin");
        Long planId = createPlan(token);
        Long accountId = createAccount(token);
        try {
            String rechargeBody = this.objectMapper.writeValueAsString(
                    new BillingRechargeDTO(new BigDecimal("100.00"), "首次充值"));
            this.mockMvc.perform(post("/api/billing/accounts/" + accountId + "/recharges")
                            .header("satoken", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(rechargeBody))
                    .andExpect(status().isOk());

            String response = this.mockMvc.perform(get("/api/billing/transactions")
                            .header("satoken", token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            JsonNode rows = readData(response);
            assertThat(rows.isArray()).isTrue();
            JsonNode first = rows.get(0);
            assertThat(first.get("type").asString()).isEqualTo("recharge");
            assertThat(first.get("amount").decimalValue())
                    .as("充值金额应为正；扣费才是负数")
                    .isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(first.get("balanceAfter").decimalValue())
                    .isEqualByComparingTo(new BigDecimal("100.00"));
        }
        finally {
            removeAccount(token, accountId);
            removePlan(token, planId);
        }
    }

    @Test
    @DisplayName("重复开户被拒（账户表对租户唯一）")
    void rejectsDuplicateAccount() throws Exception {
        String token = token("admin");
        Long planId = createPlan(token);
        Long accountId = createAccount(token);
        try {
            String body = this.objectMapper.writeValueAsString(
                    new BillingAccountSaveDTO(this.planCode, 1000L, "active"));
            this.mockMvc.perform(post("/api/billing/accounts")
                            .header("satoken", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        finally {
            removeAccount(token, accountId);
            removePlan(token, planId);
        }
    }

    @Test
    @DisplayName("开户指向不存在的套餐被拒（写侧也保证引用有效）")
    void rejectsUnknownPlan() throws Exception {
        String token = token("admin");
        String body = this.objectMapper.writeValueAsString(
                new BillingAccountSaveDTO("no_such_plan_" + UUID.randomUUID(), 1L, "active"));

        this.mockMvc.perform(post("/api/billing/accounts")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("按账期开票：无扣费流水时金额为 0，且同账期不可重复开票")
    void invoiceGenerationAndDuplicateGuard() throws Exception {
        String token = token("admin");

        String response = this.mockMvc.perform(post("/api/billing/invoices")
                        .header("satoken", token)
                        .param("period", "2099-12"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode invoice = readData(response);
        assertThat(invoice.get("period").asString()).isEqualTo("2099-12");
        assertThat(invoice.get("totalTokens").asLong()).isZero();

        this.mockMvc.perform(post("/api/billing/invoices")
                        .header("satoken", token)
                        .param("period", "2099-12"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("viewer 不能开户（写权限仅 admin）")
    void viewerCannotCreateAccount() throws Exception {
        String viewer = token("viewer");
        String body = this.objectMapper.writeValueAsString(
                new BillingAccountSaveDTO("any_plan", 1L, "active"));

        this.mockMvc.perform(post("/api/billing/accounts")
                        .header("satoken", viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    // ===== 辅助 =====

    private Long createPlan(String token) throws Exception {
        this.planCode = "acct_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String body = this.objectMapper.writeValueAsString(
                new BillingPlanSaveDTO(this.planCode, "账户测试套餐", 1_000_000L, new BigDecimal("10")));
        String response = this.mockMvc.perform(post("/api/billing/plans")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return readData(response).asLong();
    }

    private Long createAccount(String token) throws Exception {
        String body = this.objectMapper.writeValueAsString(
                new BillingAccountSaveDTO(this.planCode, 1_000_000L, "active"));
        String response = this.mockMvc.perform(post("/api/billing/accounts")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return readData(response).asLong();
    }

    private void removeAccount(String token, Long id) throws Exception {
        TenantContext.set("DEMO", 1L);
        try {
            this.accountMapper.deleteById(id);
        }
        finally {
            TenantContext.clear();
        }
    }

    private void removePlan(String token, Long id) throws Exception {
        this.mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/billing/plans/" + id).header("satoken", token))
                .andExpect(status().isOk());
    }

}
