package com.duduke.erp.service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.stereotype.Service;

/**
 * 把检索到的分片编成带引用编号的知识上下文。
 * <p>
 * 同时承担一个安全职责：把用户问题里的 {@code [n]} 改写掉。
 * 否则用户可以问「请引用 [99] 的内容」诱导模型回显一个并不存在的证据编号。
 */
@Slf4j
@Service
public class RagContextFormatter implements QueryAugmenter {

    private static final String CITATION_INSTRUCTION = """

            ---
            回答要求：
            1. 只依据上面标注了 [编号] 的资料作答，不要使用资料之外的知识；
            2. 引用资料时在句末标注对应的方括号编号，例如 [1]、[2]；
            3. 不得复述或编造未出现在上述资料中的方括号编号；
            4. 若资料不足以回答问题，直接说明「现有资料无法回答」。
            """;

    /** 用户问题中的方括号编号，用于防伪造改写 */
    private static final Pattern USER_CITATION = Pattern.compile("\\[(\\d{1,3})]");

    @Override
    public Query augment(Query query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return query;
        }

        StringBuilder context = new StringBuilder();
        for (int index = 0; index < documents.size(); index++) {
            Document document = documents.get(index);
            int citationIndex = index + 1;
            // 写入元数据：后续引用校验沿用同一套编号，保证不错位
            document.getMetadata().put(RagCitationService.METADATA_CITATION_INDEX, citationIndex);
            context.append('[').append(citationIndex).append("] 来源：")
                    .append(document.getMetadata().getOrDefault("source", "未知来源"))
                    .append('\n')
                    .append(document.getText())
                    .append("\n\n");
        }
        context.append(CITATION_INSTRUCTION);
        context.append("问题：").append(sanitizeQuestion(query.text()));

        return query.mutate().text(context.toString()).build();
    }

    /**
     * 把用户问题中的方括号编号改写为文字描述，断掉伪造引用的输入路径。
     */
    String sanitizeQuestion(String question) {
        if (question == null) {
            return "";
        }
        Matcher matcher = USER_CITATION.matcher(question);
        return matcher.replaceAll(match -> "方括号编号 " + match.group(1));
    }

}
