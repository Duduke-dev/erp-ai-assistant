package com.duduke.erp.service;

import java.util.ArrayList;
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
 * 同时承担两个职责：
 * <ol>
 *   <li><b>编号与拼装</b> —— 给每个分片编号 {@code [n]} 并要求模型只能用出现过的编号；</li>
 *   <li><b>防伪造</b> —— 把用户问题里的 {@code [n]} 改写掉，
 *       否则用户可以问「请引用 [99] 的内容」诱导模型回显一个并不存在的证据编号。</li>
 * </ol>
 *
 * <h3>为什么要把本轮文档暂存到 ThreadLocal</h3>
 * 引用校验需要拿到「本轮实际召回了哪些文档」。Spring AI 的
 * {@code RetrievalAugmentationAdvisor} 虽然声明了 {@code DOCUMENT_CONTEXT} 常量
 * 并把文档放进 response 元数据，但实测（2026-09-13，Spring AI 2.0.1）
 * 反编译可见 {@code after()} 是从 {@code ChatClientResponse.context()} 取值，
 * 而文档是在 {@code before()} 里写进新构造的 <b>request</b> context 的，
 * 两者的传递链路不保证打通——实际结果是 **key 存在但值为空列表**。
 * <p>
 * 若不另想办法，引用校验拿到的永远是空证据集，
 * 表现为「模型回答里明明有 [1][2]，但 citations 返回空」——
 * 属于静默失效：不报错、不告警，只是功能没了。
 * <p>
 * 文档正是在本类的 {@link #augment} 里被编号的，此处暂存最自然，
 * 且与校验阶段用的是<b>同一批对象</b>，编号不会错位。
 * <p>
 * 用 ThreadLocal 而非全局 Map：一轮问答内请求与编排在同一线程，
 * 不存在跨请求污染；用完即清，不需要额外的过期策略。
 * 异步 / Reactor 场景由 {@code ContextPropagationConfig} 的上下文传播覆盖。
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

    /**
     * 本轮召回文档的暂存位。
     * <p>
     * 同一个线程上可能因为异常残留上一次的数据，因此每次 {@code augment} 都<b>整体替换</b>
     * 而不是追加，读取后由编排层清理。
     */
    private static final ThreadLocal<List<Document>> RECALLED = new ThreadLocal<>();

    /**
     * 取本轮召回的文档，供引用校验使用。
     *
     * @return 本轮文档；未挂 RAG 或检索为空时返回空列表
     */
    public static List<Document> recalledDocuments() {
        List<Document> documents = RECALLED.get();
        return documents == null ? List.of() : documents;
    }

    /**
     * 清理暂存。必须在请求结束的 {@code finally} 中调用——
     * Tomcat 线程复用，不清理会让下一次请求读到上一次的证据，
     * 造成引用串档（比丢失引用更严重）。
     */
    public static void clearRecalledDocuments() {
        RECALLED.remove();
    }

    @Override
    public Query augment(Query query, List<Document> documents) {
        List<Document> usable = documents == null ? List.of() : documents;
        // 整体替换而非追加，避免线程复用导致上一轮数据串入
        RECALLED.set(new ArrayList<>(usable));

        if (usable.isEmpty()) {
            // 检索无命中：不拼上下文，模型将按系统提示词如实说明资料不足。
            // 这一条要留痕——「有知识库却召回为空」是配置或数据问题的第一现场信号。
            log.info("知识检索无命中，本轮不注入上下文");
            return query;
        }
        log.debug("知识检索命中 {} 个分片，已注入上下文", usable.size());

        StringBuilder context = new StringBuilder();
        for (int index = 0; index < usable.size(); index++) {
            Document document = usable.get(index);
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
