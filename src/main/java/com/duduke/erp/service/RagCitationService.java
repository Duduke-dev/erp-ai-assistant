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

    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d{1,3})]");

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
            if (seen.contains(index) || citations.size() >= MAX_CITATION_COUNT) {
                continue;
            }
            if (index < 1 || index > allowed.size()) {
                // 越界编号：模型编造的引用，直接丢弃
                continue;
            }
            Document document = allowed.get(index - 1);
            RagCitation citation = toCitation(index, document);
            int size = jsonSize(citation);
            if (accumulatedBytes + size > MAX_CITATION_JSON_BYTES) {
                break;
            }
            accumulatedBytes += size;
            seen.add(index);
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

    private void collectFromLine(String line, List<Integer> numbers) {
        int cursor = 0;
        boolean inInlineCode = false;
        while (cursor < line.length()) {
            char current = line.charAt(cursor);
            if (current == '`') {
                inInlineCode = !inInlineCode;
                cursor++;
                continue;
            }
            if (!inInlineCode && current == '[' && !isEscaped(line, cursor)) {
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
