package com.duduke.erp.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duduke.erp.common.exception.BusinessException;
import com.duduke.erp.entity.po.BillingAccount;
import com.duduke.erp.entity.po.BillingPriceRule;
import com.duduke.erp.entity.po.BillingTransaction;
import com.duduke.erp.entity.po.TokenUsageDaily;
import com.duduke.erp.entity.po.TokenUsageMonthly;
import com.duduke.erp.entity.vo.BillingAccountVO;
import com.duduke.erp.entity.vo.TokenUsageVO;
import com.duduke.erp.mapper.BillingAccountMapper;
import com.duduke.erp.mapper.BillingPriceRuleMapper;
import com.duduke.erp.mapper.BillingTransactionMapper;
import com.duduke.erp.mapper.TokenUsageDailyMapper;
import com.duduke.erp.mapper.TokenUsageMonthlyMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

/**
 * 计费查询服务（本租户视角）。
 * <p>
 * 全部查询都<b>不写租户条件</b>：三张表都有 {@code ent_code} 且不在 ignore-tables 中，
 * MP 插件会自动注入。手写等于多一处漏写的风险。
 * <p>
 * 当前只覆盖只读路径（账户 + 用量）。扣费与配额校验属下一片，
 * 因为那需要与问答链路协同（调用前校验、调用后按实际用量扣减），
 * 单独做一半会留下「校验了但不扣」这类半成品语义。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BillingService {

    /** 账期格式，与 {@code token_usage_monthly.period} 一致 */
    private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 不传区间时的默认回溯天数 */
    private static final int DEFAULT_RANGE_DAYS = 30;

    /** 账户可用状态 */
    private static final String STATUS_ACTIVE = "active";

    /** 交易类型：扣费 */
    private static final String TYPE_DEDUCTION = "deduction";

    private final BillingAccountMapper accountMapper;

    private final TokenUsageDailyMapper dailyMapper;

    private final TokenUsageMonthlyMapper monthlyMapper;

    private final BillingPriceRuleMapper priceRuleMapper;

    private final BillingTransactionMapper transactionMapper;

    /** 用量采集。扣费与采集总在同一时刻发生，故由本服务统一编排 */
    private final TokenUsageRecorder tokenUsageRecorder;

    /**
     * 本租户计费账户。
     *
     * @return 未开户时返回 {@code null}（前端据此提示「未开通」，
     *         而不是展示一个全 0 的假账户）
     */
    public BillingAccountVO account() {
        BillingAccount account = currentAccount();
        if (account == null) {
            return null;
        }
        long quota = account.getMonthlyQuota() == null ? 0L : account.getMonthlyQuota();
        long used = account.getUsedTokens() == null ? 0L : account.getUsedTokens();
        // 剩余允许为负：超额就是超额。夹到 0 会把「已欠额度」掩盖成「刚好用完」
        return new BillingAccountVO(account.getPlanCode(), account.getBalance(),
                quota, used, quota - used, account.getStatus());
    }

    /**
     * 日用量明细（按天 × 模型的原始粒度）。
     *
     * @param from 起始日（含）；为空时默认回溯 {@value #DEFAULT_RANGE_DAYS} 天
     * @param to   截止日（含）；为空时默认今天
     */
    public List<TokenUsageVO> dailyUsage(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_RANGE_DAYS) : from;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("起始日期不能晚于截止日期");
        }
        List<TokenUsageDaily> rows = this.dailyMapper.selectList(
                Wrappers.<TokenUsageDaily>lambdaQuery()
                        .between(TokenUsageDaily::getUsageDate, start, end)
                        .orderByAsc(TokenUsageDaily::getUsageDate));
        return rows.stream()
                .map(row -> new TokenUsageVO(String.valueOf(row.getUsageDate()),
                        row.getModelName(), row.getTotalTokens(), row.getRequestCount()))
                .toList();
    }

    /**
     * 月用量明细。
     *
     * @param from 起始账期（含），形如 {@code 2026-09}；为空时默认当月
     * @param to   截止账期（含）；为空时默认当月
     */
    public List<TokenUsageVO> monthlyUsage(String from, String to) {
        String end = to == null || to.isBlank() ? LocalDate.now().format(PERIOD_FORMAT) : to;
        String start = from == null || from.isBlank() ? end : from;
        // 账期是定长 yyyy-MM，字典序即时间序，可以直接用字符串比较
        if (start.compareTo(end) > 0) {
            throw new IllegalArgumentException("起始账期不能晚于截止账期");
        }
        List<TokenUsageMonthly> rows = this.monthlyMapper.selectList(
                Wrappers.<TokenUsageMonthly>lambdaQuery()
                        .between(TokenUsageMonthly::getPeriod, start, end)
                        .orderByAsc(TokenUsageMonthly::getPeriod));
        return rows.stream()
                .map(row -> new TokenUsageVO(row.getPeriod(),
                        row.getModelName(), row.getTotalTokens(), row.getRequestCount()))
                .toList();
    }

    // ===== 配额校验与扣费 =====

    /**
     * 调用模型前校验配额，超额<b>直接拒绝</b>：
     * 用户会收到明确的业务错误，而不是一句照常生成的回答。
     * <p>
     * <b>未开户直接放行</b>：「没配计费」不等于「不能用」。若这里连
     * {@code account == null} 也拦，一次配置疏漏就会让整个助手不可用，
     * 故障面远大于收益。
     * <p>
     * <b>配额为 0 视为不限</b>：0 表示「没设配额」，与「配额已用尽」是两件事。
     */
    public void assertQuotaAvailable() {
        BillingAccount account = currentAccount();
        if (account == null) {
            return;
        }
        if (!STATUS_ACTIVE.equals(account.getStatus())) {
            throw new BusinessException("计费账户状态异常：" + account.getStatus() + "，请联系管理员");
        }
        long quota = value(account.getMonthlyQuota());
        long used = value(account.getUsedTokens());
        if (quota > 0 && used >= quota) {
            throw new BusinessException(
                    "本月 token 配额已用尽（" + used + "/" + quota + "），请联系管理员调整套餐");
        }
    }

    /**
     * 记录一轮问答的用量，并扣减配额与余额、写交易流水。
     * <p>
     * <b>旁路</b>：任一步失败只记 warn，绝不冒泡——回答已经产生，
     * 不能因为「账没记上」把它变成失败。
     */
    public void recordConsumption(String modelName, Integer promptTokens,
                                  Integer completionTokens, Integer totalTokens) {
        this.tokenUsageRecorder.record(modelName, promptTokens, completionTokens, totalTokens);
        try {
            applyDeduction(modelName, value(promptTokens), value(completionTokens),
                    value(totalTokens));
        }
        catch (RuntimeException e) {
            log.warn("计费扣减失败（不影响问答）：{}", e.getMessage(), e);
        }
    }

    private void applyDeduction(String modelName, long promptTokens, long completionTokens,
                                long totalTokens) {
        if (totalTokens <= 0) {
            return;
        }
        BillingAccount account = currentAccount();
        if (account == null) {
            return;   // 未开户：用量已记下，无账户可扣
        }
        BigDecimal amount = computeAmount(modelName, promptTokens, completionTokens);
        BigDecimal balanceAfter = value(account.getBalance()).subtract(amount);

        account.setUsedTokens(value(account.getUsedTokens()) + totalTokens);
        account.setBalance(balanceAfter);
        this.accountMapper.updateById(account);

        BillingTransaction transaction = new BillingTransaction();
        transaction.setTransactionNo(UUID.randomUUID().toString().replace("-", ""));
        transaction.setType(TYPE_DEDUCTION);
        transaction.setAmount(amount.negate());
        transaction.setBalanceAfter(balanceAfter);
        transaction.setTokens(totalTokens);
        transaction.setRemark("对话消耗");
        this.transactionMapper.insert(transaction);
    }

    /**
     * 按「不晚于今天」的最新价格规则计算金额。
     * <p>
     * 没有匹配的价格规则时<b>记 0 元</b>而不抛异常：缺价格配置是运营疏漏，
     * 不该让用户的问答失败；用量已经记下，补齐价格规则后可另行追溯。
     */
    private BigDecimal computeAmount(String modelName, long promptTokens, long completionTokens) {
        if (modelName == null) {
            return BigDecimal.ZERO;
        }
        BillingPriceRule rule = this.priceRuleMapper.selectOne(Wrappers.<BillingPriceRule>lambdaQuery()
                .eq(BillingPriceRule::getModelName, modelName)
                .le(BillingPriceRule::getEffectiveDate, LocalDate.now())
                .orderByDesc(BillingPriceRule::getEffectiveDate)
                .last("LIMIT 1"));
        if (rule == null) {
            return BigDecimal.ZERO;
        }
        // 单价按「每千 token」定义，先乘数量再除 1000；
        // 中间保留 6 位避免过早舍入，最终按金额精度留 2 位
        BigDecimal perThousand = BigDecimal.valueOf(1000);
        BigDecimal promptCost = value(rule.getInputPrice())
                .multiply(BigDecimal.valueOf(promptTokens))
                .divide(perThousand, 6, RoundingMode.HALF_UP);
        BigDecimal completionCost = value(rule.getOutputPrice())
                .multiply(BigDecimal.valueOf(completionTokens))
                .divide(perThousand, 6, RoundingMode.HALF_UP);
        return promptCost.add(completionCost).setScale(2, RoundingMode.HALF_UP);
    }

    /** 本租户账户（唯一索引保证最多一条） */
    private BillingAccount currentAccount() {
        return this.accountMapper.selectOne(
                Wrappers.<BillingAccount>lambdaQuery().last("LIMIT 1"));
    }

    private static long value(Long number) {
        return number == null ? 0L : number;
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }

    private static BigDecimal value(BigDecimal number) {
        return number == null ? BigDecimal.ZERO : number;
    }

}
