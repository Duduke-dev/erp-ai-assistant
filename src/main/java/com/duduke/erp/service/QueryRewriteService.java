package com.duduke.erp.service;

import java.util.List;
import java.util.regex.Pattern;

import com.duduke.erp.component.springai.AssistantClientProvider;
import com.duduke.erp.config.RagProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 检索查询改写：把用户问题整理成更适合向量检索的查询。
 *
 * <h3>两层改写</h3>
 * <ol>
 *   <li><b>规则式补全</b>（总是执行）：把「省略式追问」补成可独立检索的查询。
 *       多轮对话里用户常省略主语（「那上个月呢」「这个呢」），这类问句直接做向量检索
 *       几乎必然一无所获——它不承载任何可匹配的语义。补上上一轮主语后，
 *       「主仓库存情况 上个月」才有可检索的语义；</li>
 *   <li><b>模型式润色</b>（由 {@code app.rag.rewrite-mode=model} 开启）：
 *       把口语化问句整理成贴近知识库语料的表述，进一步提升召回。</li>
 * </ol>
 *
 * <h3>为什么默认只用规则式</h3>
 * 规则式<b>确定、可单测、零成本、不会引入新失败点</b>；模型式要多一次 LLM 调用
 * （延迟 + token），并且<b>改写幅度一旦过大，检索会偏离用户原意——而这种偏离看起来
 * 完全正常、极难发现</b>。所以做成开关，用对照实验确认收益后再切换。
 * （本类原先写的「本项目当前没有可用的模型 Key」，该前提已不再成立。）
 *
 * <h3>降级链</h3>
 * 模型润色失败、或产出不可用时<b>回退到规则式结果</b>——润色是增强，不能变成新的失败点。
 *
 * <h3>只在明确的追问上做规则改写</h3>
 * 判据刻意保守：<b>只认「以连接词开头」或「以『呢』结尾」</b>两种形态。
 * 宁可漏改（退回原问题，检索不到也不会错），
 * 不可误改——把独立问题拼上上一轮主语，会引入上一轮的语义噪声，
 * 让检索结果偏离用户真实意图，而且这种偏离<b>看起来很正常</b>，很难发现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriteService {

    /** 追问的连接词开头，如「那上个月呢」「还有呢」「另外呢」 */
    private static final Pattern LEADING_CONNECTOR = Pattern.compile(
            "^(?:那么|那|还有|另外|接着|然后|它的?|该|这个|那个|上述)[，,、\\s]*");

    /** 省略式追问的典型结尾 */
    private static final Pattern TRAILING_ELLIPSIS = Pattern.compile("[呢？?。！!\\s]+$");

    /** 剥掉模型产出上的包装：引号、反引号、项目符号、行首编号 */
    private static final Pattern POLISHED_WRAPPER = Pattern.compile(
            "^[\\s\"'“”‘’`*\\-\\d.、)）]+|[\\s\"'“”‘’`*]+$");

    /** 换行折叠：检索查询必须是一行，模型偶尔仍会换行 */
    private static final Pattern ANY_NEWLINE = Pattern.compile("\\s*\\r?\\n\\s*");

    /** 改写后最多保留的锚点长度，避免把一长段上一轮问题整段搬进来 */
    private static final int MAX_ANCHOR_LENGTH = 60;

    /** 判定为追问的最长长度；超过这个长度的问题通常自带完整语义 */
    private static final int MAX_FOLLOW_UP_LENGTH = 20;

    /** 润色结果的长度上限：明显超长说明模型在「解释」而不是「改写」 */
    private static final int MAX_POLISHED_LENGTH = 200;

    /**
     * 润色的系统提示词。
     * <p>
     * 措辞刻意保守：改写的收益来自「表述更贴近语料」，一旦越过语义边界就变成损害，
     * 而且这种损害不可见。所以第 1 条强调不得改变语义，第 5 条给它留了「不改」的退路。
     */
    private static final String POLISH_SYSTEM_PROMPT = """
            你是检索查询改写器。把用户问题改写成更适合向量检索的表述，严格遵守：
            1. 只调整表述方式，绝不改变语义、不新增原问题没有的信息；
            2. 补齐指代不明的主语或宾语（可参考给出的上一轮提问）；
            3. 原样保留产品名、编号、时间、金额、数量等关键实体；
            4. 只输出改写后的**一行纯文本**：不要解释、不要引号、不要 Markdown、不要编号；
            5. 若问题本身已经清晰，直接原样输出即可。""";

    private final AssistantClientProvider clientProvider;

    private final RagProperties ragProperties;

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
        // 第一层：规则式补全省略式追问。总是执行——零成本，且是模型润色的输入基础
        String ruleResult = rewriteByRule(question, previousUserQuestions);
        if (!"model".equalsIgnoreCase(this.ragProperties.getRewriteMode())) {
            return ruleResult;
        }
        // 第二层：模型润色。失败就退回规则式结果
        return polishByModel(ruleResult, previousUserQuestions);
    }

    /**
     * 规则式改写：只处理「省略式追问」，其余原样返回。
     */
    private String rewriteByRule(String question, List<String> previousUserQuestions) {
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
     * 模型润色。
     * <p>
     * 三处刻意的保守设计：
     * <ol>
     *   <li>{@code clientProvider} 为 null 时直接跳过——纯单测会 {@code new} 本类而不启 Spring，
     *       而规则式路径本来就不需要它；</li>
     *   <li>产出必须过 {@link #isUsable}：空、或明显超长（说明模型在解释而不是改写）
     *       都按不可用处理，回退规则式结果；</li>
     *   <li>任何异常都吞掉并回退——润色是增强手段，不该成为检索的新失败点。</li>
     * </ol>
     */
    private String polishByModel(String query, List<String> previousUserQuestions) {
        if (this.clientProvider == null) {
            return query;
        }
        try {
            String polished = this.clientProvider.client()
                    .prompt()
                    .system(POLISH_SYSTEM_PROMPT)
                    .user(buildPolishUserPrompt(query, previousUserQuestions))
                    .call()
                    .content();
            String cleaned = cleanPolished(polished);
            if (!isUsable(cleaned)) {
                log.warn("查询润色结果不可用，回退规则式结果：原始长度={}",
                        polished == null ? 0 : polished.length());
                return query;
            }
            if (log.isDebugEnabled()) {
                log.debug("检索查询已润色：'{}' -> '{}'", query, cleaned);
            }
            return cleaned;
        }
        catch (RuntimeException e) {
            log.warn("查询润色失败，回退规则式结果：{}", e.getMessage());
            return query;
        }
    }

    private String buildPolishUserPrompt(String query, List<String> previousUserQuestions) {
        String anchor = lastNonBlank(previousUserQuestions);
        return anchor == null
                ? "用户问题：" + query
                : "上一轮提问：" + truncate(anchor) + "\n用户问题：" + query;
    }

    /** 剥掉引号/反引号/项目符号/行首编号，并把多行折叠成一行 */
    private String cleanPolished(String raw) {
        if (raw == null) {
            return null;
        }
        return POLISHED_WRAPPER.matcher(ANY_NEWLINE.matcher(raw.trim()).replaceAll(" "))
                .replaceAll("")
                .trim();
    }

    private boolean isUsable(String polished) {
        return StringUtils.hasText(polished) && polished.length() <= MAX_POLISHED_LENGTH;
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
