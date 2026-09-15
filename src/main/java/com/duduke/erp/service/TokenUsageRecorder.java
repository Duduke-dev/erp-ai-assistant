package com.duduke.erp.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duduke.erp.entity.po.TokenUsageDaily;
import com.duduke.erp.entity.po.TokenUsageMonthly;
import com.duduke.erp.mapper.TokenUsageDailyMapper;
import com.duduke.erp.mapper.TokenUsageMonthlyMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * token 用量采集：把一轮问答的实际用量累加进日 / 月两张表。
 *
 * <h3>旁路，不能影响主链路</h3>
 * 记录失败只记 warn，绝不冒泡——正在进行的问答不能因为「用量记不上」而失败。
 * 这与 Tool 调用流水是同一原则。
 *
 * <h3>取消的轮次也要记</h3>
 * 用户点「停止」时已产生的 token 是真实消耗，按观测用量照记。
 * 因此本记录器<b>不关心 status</b>，只认「有没有拿到用量」。
 *
 * <h3>模型名为空时必须用 IS NULL，不能用 eq(col, null)</h3>
 * {@code .eq(col, null)} 生成的是 {@code col = null}，在 SQL 里<b>永远不成立</b>：
 * 结果是每次查询都查不到、每次都走 insert，最终撞唯一索引报错。
 * 空模型名表示「未按模型细分的汇总行」，语义上本就该匹配 NULL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenUsageRecorder {

    private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final TokenUsageDailyMapper dailyMapper;

    private final TokenUsageMonthlyMapper monthlyMapper;

    /**
     * 记录一轮问答的用量。
     *
     * @param modelName        模型名；为空表示不按模型细分
     * @param promptTokens     输入 token
     * @param completionTokens 输出 token
     * @param totalTokens      合计 token；<b>小于等于 0 时直接跳过</b>——
     *                         拿不到用量时不该造出一行 0 数据，否则统计会被稀释
     */
    public void record(String modelName, Integer promptTokens, Integer completionTokens,
                       Integer totalTokens) {
        int total = value(totalTokens);
        if (total <= 0) {
            return;
        }
        try {
            LocalDate today = LocalDate.now();
            String model = StringUtils.hasText(modelName) ? modelName.trim() : null;
            upsertDaily(today, model, value(promptTokens), value(completionTokens), total);
            upsertMonthly(today.format(PERIOD_FORMAT), model, total);
        }
        catch (RuntimeException e) {
            log.warn("记录 token 用量失败（不影响问答）：{}", e.getMessage(), e);
        }
    }

    private void upsertDaily(LocalDate date, String model,
                             int promptTokens, int completionTokens, int totalTokens) {
        TokenUsageDaily existing = this.dailyMapper.selectOne(
                dailyKey(date, model).last("LIMIT 1"));
        if (existing == null) {
            TokenUsageDaily row = new TokenUsageDaily();
            row.setUsageDate(date);
            row.setModelName(model);
            row.setPromptTokens((long) promptTokens);
            row.setCompletionTokens((long) completionTokens);
            row.setTotalTokens((long) totalTokens);
            row.setRequestCount(1);
            this.dailyMapper.insert(row);
            return;
        }
        existing.setPromptTokens(nullToZero(existing.getPromptTokens()) + promptTokens);
        existing.setCompletionTokens(nullToZero(existing.getCompletionTokens()) + completionTokens);
        existing.setTotalTokens(nullToZero(existing.getTotalTokens()) + totalTokens);
        existing.setRequestCount(existing.getRequestCount() == null
                ? 1 : existing.getRequestCount() + 1);
        this.dailyMapper.updateById(existing);
    }

    private void upsertMonthly(String period, String model, int totalTokens) {
        TokenUsageMonthly existing = this.monthlyMapper.selectOne(
                monthlyKey(period, model).last("LIMIT 1"));
        if (existing == null) {
            TokenUsageMonthly row = new TokenUsageMonthly();
            row.setPeriod(period);
            row.setModelName(model);
            row.setTotalTokens((long) totalTokens);
            row.setRequestCount(1);
            this.monthlyMapper.insert(row);
            return;
        }
        existing.setTotalTokens(nullToZero(existing.getTotalTokens()) + totalTokens);
        existing.setRequestCount(existing.getRequestCount() == null
                ? 1 : existing.getRequestCount() + 1);
        this.monthlyMapper.updateById(existing);
    }

    /** 日表唯一键（不含 ent_code：由租户插件注入） */
    private LambdaQueryWrapper<TokenUsageDaily> dailyKey(LocalDate date, String model) {
        LambdaQueryWrapper<TokenUsageDaily> wrapper = Wrappers.<TokenUsageDaily>lambdaQuery()
                .eq(TokenUsageDaily::getUsageDate, date);
        return applyModel(wrapper, model, TokenUsageDaily::getModelName);
    }

    /** 月表唯一键（不含 ent_code：由租户插件注入） */
    private LambdaQueryWrapper<TokenUsageMonthly> monthlyKey(String period, String model) {
        LambdaQueryWrapper<TokenUsageMonthly> wrapper = Wrappers.<TokenUsageMonthly>lambdaQuery()
                .eq(TokenUsageMonthly::getPeriod, period);
        return applyModel(wrapper, model, TokenUsageMonthly::getModelName);
    }

    private <T> LambdaQueryWrapper<T> applyModel(LambdaQueryWrapper<T> wrapper, String model,
                                                 com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> column) {
        if (model == null) {
            // 必须 IS NULL：eq(col, null) 生成 `col = null`，SQL 里恒不成立
            wrapper.isNull(column);
        }
        else {
            wrapper.eq(column, model);
        }
        return wrapper;
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

}
