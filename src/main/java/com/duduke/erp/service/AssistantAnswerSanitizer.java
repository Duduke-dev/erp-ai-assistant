package com.duduke.erp.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 助手最终答案净化器：把「模型调用 Tool 时的内部旁白」与「面向用户的业务回答」隔开。
 *
 * <h3>为什么需要它</h3>
 * 带 Tool Calling 的模型常先输出一段过程说明（"让我先查询销售订单…"），
 * 再给出结论。这段旁白对用户没有价值，且会让前端把中间状态当成答案渲染。
 * 净化器只保留面向用户的部分。
 *
 * <h3>两条路径</h3>
 * <ol>
 *   <li><b>边界标记</b>：模型按提示词约定在最终答案前输出
 *       {@code <!--FINAL_ANSWER-->}，标记之后的内容即最终答案。
 *       <b>当前系统提示词尚未加入该约定</b>，此路径为后续接入预留——
 *       标记不存在时不会误伤文本。</li>
 *   <li><b>兼容净化</b>（当前实际生效）：没有标记时，只删除
 *       <b>能够确定为内部过程</b>的段落。判据保守——仅当段落
 *       <b>以</b>旁白句式开头才删，正文里出现相同词组不受影响。
 *       宁可漏删，不可误删业务正文。</li>
 * </ol>
 *
 * <h3>接线状态：<b>两条链路都已接入</b></h3>
 * 非流式在 {@code AssistantService#buildSuccessResult}、流式在
 * {@code AssistantLifecycleService} 收口处，且都在<b>引用校验之前</b>调用——
 * 旁白里的 {@code [n]} 编号不该被当成引用。
 * 两条一起接是刻意的：只接一条会造成「非流式干净、流式带旁白」的分歧。
 * <p>
 * 另：流式<b>只对成功轮次净化</b>。取消/失败时文本是半截的，
 * 此时删旁白可能把仅有的一点内容也删掉，故原样保留（status 字段已标明）。
 *
 * <h3>刻意不做的事</h3>
 * 不做「流式分片净化」。参考实现为此维护了跨分片的暂存会话与残缺标记处理，
 * 而本项目流式走 {@code SseEmitter}（非 {@code Flux}），要接进来得改动 SSE 管线。
 * 当前采用「回答完成后净化一次再落库」的等价处理。
 */
@Component
public class AssistantAnswerSanitizer {

    /** 模型最终答案边界标记，仅用于后端协议，不向前端返回 */
    public static final String FINAL_ANSWER_MARKER = "<!--FINAL_ANSWER-->";

    /** 兼容旧消息中用于分隔内部过程与最终答案的 Markdown 水平线 */
    private static final Pattern FINAL_SECTION_SEPARATOR = Pattern.compile("(?m)^\\s*---\\s*$");

    /** 可单独确认内部图表规划失败的协议标记 */
    private static final String INTERNAL_PLAN_REJECTION_MARKER = "accepted=false";

    /** 可明确识别为内部执行步骤的段落开头（中文） */
    private static final List<String> INTERNAL_NARRATION_PREFIXES = List.of(
            "让我", "我需要先", "我先", "我尝试", "我将", "接下来我", "现在我", "不过，我");

    /** 英文内部执行步骤的前导句式 */
    private static final Pattern LEADING_ENGLISH_INTERNAL_NARRATION = Pattern.compile(
            "^(?:I\\s+(?:need|have)\\s+to|I(?:'|’)ll|I\\s+will|Let\\s+me|"
                    + "First[,\\s]+I(?:'|’)ll|Now[,\\s]+I(?:'|’)ll)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 英文旁白必须包含的查询或规划动作，避免误伤普通英文开场白 */
    private static final Pattern INTERNAL_ENGLISH_ACTION = Pattern.compile(
            "\\b(?:query|retrieve|fetch|search|check|plan|chart|visuali[sz]ation|tool|data)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 英文前导句与中文正文之间的安全切分边界 */
    private static final Pattern ENGLISH_TO_CHINESE_BOUNDARY =
            Pattern.compile("(?<=[.!?])\\s*(?=[\\p{IsHan}0-9#|])");

    /** 用于确认英文前缀之后确实存在中文业务回答 */
    private static final Pattern CHINESE_CONTENT = Pattern.compile("\\p{IsHan}");

    /**
     * 净化完整模型输出，只保留面向用户的最终业务回答。
     *
     * @param content 模型原始输出
     * @return 不含内部执行旁白与边界标记的回答；输入为空时返回空串
     */
    public String sanitize(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        int markerIndex = content.lastIndexOf(FINAL_ANSWER_MARKER);
        if (markerIndex >= 0) {
            return stripLeadingLineBreaks(
                    content.substring(markerIndex + FINAL_ANSWER_MARKER.length())).stripTrailing();
        }
        return sanitizeLegacyContent(content);
    }

    /**
     * 兼容净化：没有边界标记时，仅删除能确定为内部过程的段落。
     */
    private String sanitizeLegacyContent(String content) {
        String normalized = stripLeadingEnglishInternalNarration(content);

        // 模型用 --- 分隔「过程」与「结论」时，取最后一段作为答案
        Matcher separatorMatcher = FINAL_SECTION_SEPARATOR.matcher(normalized);
        int finalSectionStart = -1;
        while (separatorMatcher.find()) {
            finalSectionStart = separatorMatcher.end();
        }
        if (finalSectionStart >= 0) {
            String prefix = normalized.substring(0, finalSectionStart);
            String finalSection = normalized.substring(finalSectionStart).strip();
            if (!finalSection.isEmpty() && containsInternalNarration(prefix)) {
                return finalSection;
            }
        }

        String[] paragraphs = normalized.split("\\R\\s*\\R");
        List<String> retained = new ArrayList<>();
        for (String paragraph : paragraphs) {
            if (!isInternalNarrationParagraph(paragraph)) {
                retained.add(paragraph.strip());
            }
        }
        return String.join("\n\n", retained).strip();
    }

    /**
     * 判断文本是否为内部查询、规划或重试旁白。
     */
    private boolean containsInternalNarration(String content) {
        String normalized = content.stripLeading();
        return content.contains(INTERNAL_PLAN_REJECTION_MARKER)
                || INTERNAL_NARRATION_PREFIXES.stream().anyMatch(normalized::startsWith)
                || (LEADING_ENGLISH_INTERNAL_NARRATION.matcher(normalized).find()
                        && INTERNAL_ENGLISH_ACTION.matcher(normalized).find());
    }

    private boolean isInternalNarrationParagraph(String paragraph) {
        String normalized = paragraph.strip();
        return !normalized.isEmpty() && containsInternalNarration(normalized);
    }

    /**
     * 移除直接拼接在中文业务答案前的英文查询旁白。
     * <p>
     * 只在「英文前导句含查询动作」且「其后确有中文正文」时才切，
     * 两个条件缺一即原样返回——宁可漏删，不可误删业务内容。
     */
    private String stripLeadingEnglishInternalNarration(String content) {
        String normalized = content.stripLeading();
        if (!LEADING_ENGLISH_INTERNAL_NARRATION.matcher(normalized).find()) {
            return content;
        }
        Matcher boundaryMatcher = ENGLISH_TO_CHINESE_BOUNDARY.matcher(normalized);
        if (!boundaryMatcher.find()) {
            return content;
        }
        String narrationPrefix = normalized.substring(0, boundaryMatcher.end());
        String finalContent = normalized.substring(boundaryMatcher.end()).stripLeading();
        if (!INTERNAL_ENGLISH_ACTION.matcher(narrationPrefix).find()) {
            return content;
        }
        if (!CHINESE_CONTENT.matcher(finalContent).find()) {
            return content;
        }
        return finalContent;
    }

    private static String stripLeadingLineBreaks(String content) {
        int start = 0;
        while (start < content.length()
                && (content.charAt(start) == '\r' || content.charAt(start) == '\n')) {
            start++;
        }
        return content.substring(start);
    }

}
