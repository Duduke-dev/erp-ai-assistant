package com.duduke.erp.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duduke.erp.entity.po.BillingAccount;
import com.duduke.erp.entity.po.TokenUsageDaily;
import com.duduke.erp.entity.po.TokenUsageMonthly;
import com.duduke.erp.entity.vo.BillingAccountVO;
import com.duduke.erp.entity.vo.TokenUsageVO;
import com.duduke.erp.mapper.BillingAccountMapper;
import com.duduke.erp.mapper.TokenUsageDailyMapper;
import com.duduke.erp.mapper.TokenUsageMonthlyMapper;

import lombok.RequiredArgsConstructor;

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
@RequiredArgsConstructor
public class BillingService {

    /** 账期格式，与 {@code token_usage_monthly.period} 一致 */
    private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 不传区间时的默认回溯天数 */
    private static final int DEFAULT_RANGE_DAYS = 30;

    private final BillingAccountMapper accountMapper;

    private final TokenUsageDailyMapper dailyMapper;

    private final TokenUsageMonthlyMapper monthlyMapper;

    /**
     * 本租户计费账户。
     *
     * @return 未开户时返回 {@code null}（前端据此提示「未开通」，
     *         而不是展示一个全 0 的假账户）
     */
    public BillingAccountVO account() {
        BillingAccount account = this.accountMapper.selectOne(
                Wrappers.<BillingAccount>lambdaQuery().last("LIMIT 1"));
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

}
