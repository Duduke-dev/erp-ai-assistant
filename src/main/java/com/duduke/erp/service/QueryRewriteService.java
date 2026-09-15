package com.duduke.erp.service;

import java.util.List;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 检索查询改写：把「省略式追问」补全成可独立检索的查询。
 *
 * <h3>要解决的问题</h3>
 * 多轮对话里用户常省略主语：「那上个月呢」「这个呢」。
 * 这类问句直接拿去做向量检索，几乎必然检索不到任何东西——
 * 它没有承载任何可匹配的语义。补上上一轮的主语后，
 * 「主仓库存情况 上个月」才有可检索的语义。
 *
 * <h3>为什么是规则式，而不是再调一次模型</h3>
 * 参考实现没有这个能力；模型式改写要额外一次 LLM 调用（延迟 + token 成本），
 * 而本项目当前<b>没有可用的模型 Key</b>，无法验证其效果。
 * 规则式的好处是<b>确定、可单测、零成本、不会引入新失败点</b>。
 * 若将来实测规则覆盖不足，可以在这里替换为模型式实现
 * （入参出参不变），调用方无需改动。
 *
 * <h3>只在明确的追问上改写</h3>
 * 判据刻意保守：<b>只认「以连接词开头」或「以『呢』结尾」</b>两种形态。
 * 宁可漏改（退回原问题，检索不到也不会错），
 * 不可误改——把独立问题拼上上一轮主语，会引入上一轮的语义噪声，
 * 让检索结果偏离用户真实意图，而且这种偏离<b>看起来很正常</b>，很难发现。
 */
@Slf4j
@Service
public class QueryRewriteService {

    /** 追问的连接词开头，如「那上个月呢」「还有呢」「另外呢」 */
    private static final Pattern LEADING_CONNECTOR = Pattern.compile(
            "^(?:那么|那|还有|另外|接着|然后|它的?|该|这个|那个|上述)[，,、\\s]*");

    /** 省略式追问的典型结尾 */
    private static final Pattern TRAILING_ELLIPSIS = Pattern.compile("[呢？?。！!\\s]+$");

    /** 改写后最多保留的锚点长度，避免把一长段上一轮问题整段搬进来 */
    private static final int MAX_ANCHOR_LENGTH = 60;

    /** 判定为追问的最长长度；超过这个长度的问题通常自带完整语义 */
    private static final int MAX_FOLLOW_UP_LENGTH = 20;

    /**
     * 改写查询。
     *
     * @param question              本轮用户问题
     * @param previousUserQuestions 会话内的历史提问，<b>按时间正序</b>（最后一个最近）
     * @return 可独立检索的查询；不属于追问、或没有可用锚点时<b>原样返回</b>
     */
    public String rewrite(String question, List<String> previousUserQuestions) {
        if (!StringUtils.hasText(question)) {
            return question;
        }
        if (previousUserQuestions == null || previousUserQuestions.isEmpty()) {
            return question;
        }
        if (!isEllipticalFollowUp(question)) {
            return question;
        }
        String anchor = lastNonBlank(previousUserQuestions);
        if (anchor == null) {
            return question;
        }

        String focus = TRAILING_ELLIPSIS.matcher(LEADING_CONNECTOR.matcher(question).replaceFirst(""))
                .replaceFirst("").trim();
        String rewritten;
        if (focus.isEmpty()) {
            // 纯连接词追问（如「那呢？」）：锚点本身就是完整语义
            rewritten = anchor;
        }
        else {
            rewritten = truncate(anchor) + " " + focus;
        }
        if (log.isDebugEnabled()) {
            log.debug("检索查询已改写：'{}' -> '{}'", question, rewritten);
        }
        return rewritten;
    }

    /**
     * 是否为省略式追问。
     * <p>
     * 两个条件都要靠「形态」而非「语义」判断，因为形态可确定验证；
     * 语义判断在没有模型的情况下只能靠猜。
     * <p>
     * <b>长度上限是必须的</b>：像「那我想了解一下上个月主仓库的库存情况」
     * 这种以连接词开头、但本身语义完整的长问题，拼上上一轮主语只会引入噪声。
     */
    private boolean isEllipticalFollowUp(String question) {
        String trimmed = question.trim();
        if (trimmed.length() > MAX_FOLLOW_UP_LENGTH) {
            return false;
        }
        return LEADING_CONNECTOR.matcher(trimmed).find() || trimmed.endsWith("呢");
    }

    private String lastNonBlank(List<String> questions) {
        for (int index = questions.size() - 1; index >= 0; index--) {
            String candidate = questions.get(index);
            if (StringUtils.hasText(candidate)) {
                return candidate.trim();
            }
        }
        return null;
    }

    private String truncate(String value) {
        return value.length() <= MAX_ANCHOR_LENGTH
                ? value
                : value.substring(0, MAX_ANCHOR_LENGTH);
    }

}
