package com.duduke.erp.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.duduke.erp.entity.vo.RagCitation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 引用编号的提取、校验与持久化编解码。
 * <p>
 * 回答中的 {@code [n]} 是模型自由生成的，可能与实际证据无关，必须用本轮真实证据做白名单校验。
 * 校验体系与上下文拼装阶段写入的 {@code citation_index} 同源，因此不会错位。
 * <p>
 * 三类真实存在的模型偏差，都在本类里被容忍而非报错：
 * <ol>
 *   <li>编号越界（多轮下按全局累计计数）→ 按证据条数回绕归位；</li>
 *   <li>全角 {@code 【n】} 与半角 {@code [n]} 混用 → 两种括号都识别；</li>
 *   <li>编号出现在代码块或行内代码里（JSON、正则、数组下标）→ 跳过，不当作引用。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagCitationService {

    /** 单条回答最多保留的引用数，防止引用列表被模型放大 */
    private static final int MAX_CITATION_COUNT = 8;

    /** 单条引用摘要的最大长度（按 Unicode 码点计，避免截断中文与 emoji） */
    private static final int MAX_EXCERPT_CODE_POINTS = 500;

    /** 引用 JSON 的体积上限，是持久化前的第二道防线 */
    private static final int MAX_CITATION_JSON_BYTES = 32 * 1024;

    /** 元数据中承载引用编号的键，与上下文拼装阶段写入的保持一致 */
    public static final String METADATA_CITATION_INDEX = "citation_index";

    /**
     * 引用编号的形态。
     * <p>
     * 必须同时接受半角 {@code [1]} 与全角 {@code 【1】}：实测同一模型的同一问题，
     * 输出形态会在两种之间摆动（中文语境下更容易写成全角）。
     * 只认半角会导致「回答里明明有编号，citations 却为空」——静默失效，不报错。
     */
    private static final Pattern CITATION_PATTERN = Pattern.compile("[\\[【](\\d{1,3})[\\]】]");

    private final ObjectMapper objectMapper;

    /**
     * 用本轮真实证据校验回答中的引用编号。
     *
     * @param answer    模型回答
     * @param documents 本轮实际检索到的文档
     * @return 按首次出现顺序排列、且确实命中证据的引用
     */
    public List<RagCitation> validate(String answer, List<Document> documents) {
        if (answer == null || documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<Document> allowed = documents.size() > MAX_CITATION_COUNT
                ? documents.subList(0, MAX_CITATION_COUNT)
                : documents;

        List<RagCitation> citations = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        int accumulatedBytes = 0;

        for (Integer index : extractCitationNumbers(answer)) {
            if (citations.size() >= MAX_CITATION_COUNT) {
                break;
            }
            if (index < 1) {
                continue;
            }
            int normalized = normalizeIndex(index, allowed.size());
            if (normalized < 1) {
                // 编号偏离证据范围过远，判定为模型编造，丢弃
                continue;
            }
            if (seen.contains(normalized)) {
                continue;
            }
            Document document = allowed.get(normalized - 1);
            RagCitation citation = toCitation(normalized, document);
            int size = jsonSize(citation);
            if (accumulatedBytes + size > MAX_CITATION_JSON_BYTES) {
                break;
            }
            accumulatedBytes += size;
            seen.add(normalized);
            citations.add(citation);
        }
        return citations;
    }

    /**
     * 从 Markdown 正文提取引用编号，跳过代码块与转义字面量。
     * <p>
     * 回答里常有代码示例、JSON、正则，其中天然含 {@code [0]}、{@code [1]}；
     * 直接正则匹配会把它们误判成引用，污染校验结果。
     */
    public List<Integer> extractCitationNumbers(String answer) {
        List<Integer> numbers = new ArrayList<>();
        if (answer == null || answer.isEmpty()) {
            return numbers;
        }

        boolean inFence = false;
        String fenceMarker = null;
        for (String rawLine : answer.split("\n", -1)) {
            String line = rawLine.stripLeading();
            String fence = fenceMarkerOf(line);
            if (fence != null) {
                if (!inFence) {
                    inFence = true;
                    fenceMarker = fence;
                } else if (line.startsWith(fenceMarker)) {
                    inFence = false;
                    fenceMarker = null;
                }
                continue;
            }
            if (inFence) {
                continue;
            }
            collectFromLine(line, numbers);
        }
        return numbers;
    }

    /**
     * 序列化为 JSON。超出条数或体积上限时抛异常，由调用方决定降级策略。
     */
    public String encode(List<RagCitation> citations) {
        if (citations == null || citations.isEmpty()) {
            return null;
        }
        if (citations.size() > MAX_CITATION_COUNT) {
            throw new IllegalArgumentException("引用条数超限：" + citations.size());
        }
        String json = this.objectMapper.writeValueAsString(citations);
        if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CITATION_JSON_BYTES) {
            throw new IllegalArgumentException("引用体积超限");
        }
        return json;
    }

    /**
     * 反序列化。数据损坏或超限时返回空列表并告警——历史回放不应因一条脏数据整体失败。
     */
    public List<RagCitation> decode(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            RagCitation[] parsed = this.objectMapper.readValue(json, RagCitation[].class);
            return List.of(parsed);
        } catch (RuntimeException e) {
            log.warn("引用 JSON 解析失败，已降级为空引用", e);
            return List.of();
        }
    }

    /**
     * 把模型给出的编号归位到本轮的合法证据序号。
     * <p>
     * 越界是真实存在的模型偏差：多轮对话里模型会把引用编号当成<b>全局累计</b>，
     * 第二轮明明只有 2 条证据却接着写 {@code [3]}。直接丢弃会让整轮引用凭空消失
     * （实测表现为「回答里明明有编号，citations 却是空」）。
     * <p>
     * 但归位不能无边界：证据只有 1 条时，任何编号都会落到 {@code [1]}，
     * 等于把白名单校验整个关掉——模型随口写 {@code [99]} 也会被当成有效引用。
     * 因此只在编号落在「不超过证据条数两倍」的温和区间内才回绕，
     * 明显离谱的编号仍按编造处理。
     *
     * @return 归位后的序号（从 1 开始）；超出容忍区间返回 -1 表示应丢弃
     */
    private int normalizeIndex(int index, int evidenceCount) {
        if (index <= evidenceCount) {
            return index;
        }
        if (index <= evidenceCount * 2) {
            return (index - 1) % evidenceCount + 1;
        }
        return -1;
    }

    private void collectFromLine(String line, List<Integer> numbers) {        int cursor = 0;
        boolean inInlineCode = false;
        while (cursor < line.length()) {
            char current = line.charAt(cursor);
            if (current == '`') {
                inInlineCode = !inInlineCode;
                cursor++;
                continue;
            }
            if (!inInlineCode && (current == '[' || current == '【') && !isEscaped(line, cursor)) {
                Matcher matcher = CITATION_PATTERN.matcher(line);
                matcher.region(cursor, line.length());
                if (matcher.lookingAt()) {
                    numbers.add(Integer.parseInt(matcher.group(1)));
                    cursor = matcher.end();
                    continue;
                }
            }
            cursor++;
        }
    }

    /** 判断行首是否为代码围栏标记（``` 或 ~~~），是则返回标记本身 */
    private String fenceMarkerOf(String line) {
        if (line.startsWith("```")) {
            return "```";
        }
        if (line.startsWith("~~~")) {
            return "~~~";
        }
        return null;
    }

    /** 该位置的字符是否被奇数个反斜杠转义 */
    private boolean isEscaped(String text, int index) {
        int backslashes = 0;
        int cursor = index - 1;
        while (cursor >= 0 && text.charAt(cursor) == '\\') {
            backslashes++;
            cursor--;
        }
        return backslashes % 2 == 1;
    }

    private RagCitation toCitation(int index, Document document) {
        String source = String.valueOf(document.getMetadata().getOrDefault("source", "未知来源"));
        return new RagCitation(index, source, truncateByCodePoints(document.getText(), MAX_EXCERPT_CODE_POINTS));
    }

    private String truncateByCodePoints(String text, int maxCodePoints) {
        if (text == null) {
            return null;
        }
        if (text.codePointCount(0, text.length()) <= maxCodePoints) {
            return text;
        }
        int end = text.offsetByCodePoints(0, maxCodePoints);
        return text.substring(0, end);
    }

    private int jsonSize(RagCitation citation) {
        return this.objectMapper.writeValueAsString(List.of(citation))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

}
