package com.duduke.erp.rageval;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.ObjectMapper;

/**
 * 评测数据集加载：用例（JSONL，一行一条）与 fixture 文档（TXT）。
 *
 * <h3>为什么用 JSONL 而不是一个大 JSON</h3>
 * 用例是一条条增量维护的，JSONL 让人可以只改一行、也能只跑改过的那几条；
 * 大数组文件每次改动都会在 diff 里显示成整块变更，评审时看不出到底动了哪条。
 *
 * <h3>数据集按版本分目录</h3>
 * 用例一旦改动，指标就不可比——所以目录带版本号（v1），
 * 换数据集时新建 v2 并重建基线，而不是原地改 v1 让历史指标失去意义。
 */
public class RagEvalDatasetLoader {

    private static final String ROOT = "classpath:rag-eval/%s/";

    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 读取用例集。
     *
     * @param version 数据集版本目录名，如 {@code v1}
     */
    public List<RagEvalCase> loadCases(String version) {
        Resource resource = this.resolver.getResource(ROOT.formatted(version) + "cases.jsonl");
        if (!resource.exists()) {
            throw new IllegalStateException("评测用例文件不存在：" + resource.getDescription());
        }
        List<RagEvalCase> cases = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                try {
                    cases.add(this.objectMapper.readValue(trimmed, RagEvalCase.class));
                }
                catch (RuntimeException e) {
                    // 带上行号：JSONL 里定位错误只能靠行号
                    throw new IllegalStateException(
                            "评测用例第 " + lineNumber + " 行无法解析：" + trimmed, e);
                }
            }
        }
        catch (IOException e) {
            throw new IllegalStateException("读取评测用例失败：" + resource.getDescription(), e);
        }
        if (cases.isEmpty()) {
            throw new IllegalStateException("评测用例集为空：" + resource.getDescription());
        }
        return List.copyOf(cases);
    }

    /**
     * 读取 fixture 文档：文件名 → 内容。
     * <p>
     * 返回的键就是「来源」（source），用例里的 {@code expectedSources} 按它匹配。
     */
    public Map<String, byte[]> loadDocuments(String version) {
        Map<String, byte[]> documents = new LinkedHashMap<>();
        try {
            Resource[] resources = this.resolver
                    .getResources(ROOT.formatted(version) + "documents/*.txt");
            for (Resource resource : resources) {
                String fileName = resource.getFilename();
                if (fileName == null) {
                    continue;
                }
                documents.put(fileName, resource.getInputStream().readAllBytes());
            }
        }
        catch (IOException e) {
            throw new IllegalStateException("读取评测文档失败", e);
        }
        if (documents.isEmpty()) {
            throw new IllegalStateException("评测文档集为空：rag-eval/" + version + "/documents");
        }
        return Map.copyOf(documents);
    }

    /**
     * 读取基线。
     *
     * @return 基线；文件不存在或为空时返回 null，表示首次运行（不做回归比较）
     */
    public RagEvalBaseline loadBaseline(String version) {
        Resource resource = this.resolver.getResource(ROOT.formatted(version) + "baseline.json");
        if (!resource.exists()) {
            return null;
        }
        try (var input = resource.getInputStream()) {
            byte[] content = input.readAllBytes();
            if (content.length == 0) {
                return null;
            }
            return this.objectMapper.readValue(content, RagEvalBaseline.class);
        }
        catch (IOException e) {
            throw new IllegalStateException("读取评测基线失败：" + resource.getDescription(), e);
        }
    }

}
