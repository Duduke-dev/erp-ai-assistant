package com.duduke.erp;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duduke.erp.entity.po.TokenUsageDaily;
import com.duduke.erp.entity.po.TokenUsageMonthly;
import com.duduke.erp.entity.vo.TokenUsageVO;
import com.duduke.erp.mapper.TokenUsageDailyMapper;
import com.duduke.erp.mapper.TokenUsageMonthlyMapper;
import com.duduke.erp.service.BillingService;
import com.duduke.erp.service.TokenUsageRecorder;
import com.duduke.erp.tenant.TenantContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用量采集的验证。
 * <p>
 * 三条要点：
 * <ol>
 *   <li><b>累加而非新增</b>：同一天同一模型只有一行，重复记录要累加 token 与请求数
 *       （唯一索引是 {@code (ent_code, usage_date, model_name)}）；</li>
 *   <li><b>拿不到用量就不记</b>：totalTokens 为 0 / null 时不该造出 0 行，否则统计被稀释；</li>
 *   <li><b>按模型分行</b>：不同模型不能混进同一行。</li>
 * </ol>
 * 用带 UUID 的模型名隔离数据，用完按模型名清理，避免污染共享的演示库。
 */
@SpringBootTest
class TokenUsageRecorderTest {

    private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    @Autowired
    private TokenUsageRecorder recorder;

    @Autowired
    private BillingService billingService;

    @Autowired
    private TokenUsageDailyMapper dailyMapper;

    @Autowired
    private TokenUsageMonthlyMapper monthlyMapper;

    private String modelName;

    @BeforeEach
    void setUp() {
        // 记录器依赖租户上下文（ent_code 由 MP 插件注入），测试里显式设置
        TenantContext.set("DEMO", 1L);
        this.modelName = "test-model-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        // 按模型名清理，只删本用例造的数据
        deleteByModel(this.modelName);
        TenantContext.clear();
    }

    @Test
    @DisplayName("同日同模型重复记录累加到一行，请求数递增")
    void accumulatesIntoOneRow() {
        this.recorder.record(this.modelName, 100, 50, 150);
        this.recorder.record(this.modelName, 200, 100, 300);

        List<TokenUsageVO> rows = todayRows();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).totalTokens()).isEqualTo(450L);
        assertThat(rows.get(0).requestCount())
                .as("两次调用应记为 2 次请求，而不是覆盖成 1")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("totalTokens 为 0 或 null 时不产生任何行")
    void skipsWhenUsageMissing() {
        this.recorder.record(this.modelName, 0, 0, 0);
        this.recorder.record(this.modelName, null, null, null);

        assertThat(todayRows())
                .as("拿不到用量就跳过，不能造出 0 行稀释统计")
                .isEmpty();
    }

    @Test
    @DisplayName("不同模型分别成行，互不混合")
    void separatesByModel() {
        String other = this.modelName + "-b";
        this.recorder.record(this.modelName, 10, 10, 20);
        this.recorder.record(other, 30, 30, 60);
        try {
            // 按「属于本用例的两个模型名」筛，而不是只按 modelName——
            // 只按 modelName 筛会把 -b 那行排除掉，断言 2 行必然失败
            List<TokenUsageVO> rows = allTodayRows().stream()
                    .filter(row -> this.modelName.equals(row.modelName())
                            || other.equals(row.modelName()))
                    .toList();
            assertThat(rows).hasSize(2);
        }
        finally {
            deleteByModel(other);
        }
    }

    @Test
    @DisplayName("同时写入月表，且月表同样累加")
    void writesMonthlyToo() {
        this.recorder.record(this.modelName, 100, 100, 200);
        this.recorder.record(this.modelName, 100, 100, 200);

        String period = LocalDate.now().format(PERIOD_FORMAT);
        // 按本用例的模型名筛，而不是断言「当月只有 1 行」：
        // 库里存在真实用量时（例如运行过 RagEvalIT 这类会真调模型的端到端评测），
        // 后一种写法必然失败——它断言的其实是"这个库里没有别人的数据"。
        List<TokenUsageVO> monthly = this.billingService.monthlyUsage(period, period).stream()
                .filter(row -> this.modelName.equals(row.modelName()))
                .toList();

        assertThat(monthly).hasSize(1);
        assertThat(monthly.get(0).totalTokens()).isEqualTo(400L);
        assertThat(monthly.get(0).requestCount()).isEqualTo(2);
    }

    /** 本用例所造模型的当日行 */
    private List<TokenUsageVO> todayRows() {
        return allTodayRows().stream()
                .filter(row -> this.modelName.equals(row.modelName()))
                .toList();
    }

    /** 当日全部行（可能含其它用例的数据） */
    private List<TokenUsageVO> allTodayRows() {
        LocalDate today = LocalDate.now();
        return this.billingService.dailyUsage(today, today);
    }

    /** 按模型名清理日 / 月两表 */
    private void deleteByModel(String model) {
        this.dailyMapper.delete(Wrappers.<TokenUsageDaily>lambdaQuery()
                .eq(TokenUsageDaily::getModelName, model));
        this.monthlyMapper.delete(Wrappers.<TokenUsageMonthly>lambdaQuery()
                .eq(TokenUsageMonthly::getModelName, model));
    }

}
