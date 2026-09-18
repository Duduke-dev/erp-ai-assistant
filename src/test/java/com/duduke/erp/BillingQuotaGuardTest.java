package com.duduke.erp;

import java.math.BigDecimal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duduke.erp.entity.po.BillingAccount;
import com.duduke.erp.mapper.BillingAccountMapper;
import com.duduke.erp.tenant.TenantContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 配额耗尽时的拒绝行为。
 * <p>
 * 策略定为<b>拒绝</b>：超额直接返回业务错误，用户拿不到回答。
 * 校验发生在落库与建会话之前，因此被拒绝的轮次<b>不留任何痕迹</b>。
 * <p>
 * <b>注意</b>：本用例会在演示租户下插入一个配额耗尽的账户，
 * 若清理失败会影响其它走 /api/chat 的用例（它们会被一并拒绝）。
 * 因此清理放在 {@code finally} 里，且按 planCode 精确删除。
 */
class BillingQuotaGuardTest extends AbstractApiTest {

    private static final String TEST_PLAN_CODE = "quota-guard-test-plan";

    @Autowired
    private BillingAccountMapper accountMapper;

    @Test
    @DisplayName("配额用尽时拒绝回答，且提示可读")
    void rejectsWhenQuotaExhausted() throws Exception {
        insertExhaustedAccount();
        try {
            String token = testTenantToken();
            String body = """
                    {"question":"查询主仓库存","mode":"auto"}
                    """;

            String response = this.mockMvc.perform(post("/api/chat/ask")
                            .header("satoken", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            assertThat(response)
                    .as("拒绝时必须给出可读原因，而不是笼统的服务器错误")
                    .contains("配额已用尽");
        }
        finally {
            deleteTestAccount();
        }
    }

    @Test
    @DisplayName("未开户不受配额约束（没配计费不等于不能用）")
    void allowsWhenNoAccount() throws Exception {
        // 演示库默认没有账户；这里确保没有本用例的残留即可
        deleteTestAccount();

        String token = testTenantToken();
        String body = """
                {"question":"你好","mode":"knowledge"}
                """;

        // knowledge 模式不挂 RAG 检索也可运行；这里只断言「没有因配额被拒」
        // （模型不可用会以 500 收场，那不是本条要验的东西，故只排除 400 与配额无关的情况）
        int status = this.mockMvc.perform(post("/api/chat/ask")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getStatus();

        assertThat(status).as("未开户不应因配额被拒（400）").isNotEqualTo(400);
    }

    private void insertExhaustedAccount() {
        TenantContext.set(TEST_ENT_CODE, null);
        try {
            BillingAccount account = new BillingAccount();
            account.setPlanCode(TEST_PLAN_CODE);
            account.setBalance(BigDecimal.ZERO);
            account.setMonthlyQuota(100L);
            account.setUsedTokens(100L);   // 用满
            account.setStatus("active");
            this.accountMapper.insert(account);
        }
        finally {
            TenantContext.clear();
        }
    }

    private void deleteTestAccount() {
        TenantContext.set(TEST_ENT_CODE, null);
        try {
            this.accountMapper.delete(Wrappers.<BillingAccount>lambdaQuery()
                    .eq(BillingAccount::getPlanCode, TEST_PLAN_CODE));
        }
        finally {
            TenantContext.clear();
        }
    }

}
