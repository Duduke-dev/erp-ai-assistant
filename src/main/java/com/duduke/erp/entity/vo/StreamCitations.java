package com.duduke.erp.entity.vo;

import java.util.List;

/**
 * 引用证据事件。
 * <p>
 * 流结束时一次性下发，因为引用校验需要<b>完整的回答文本</b>才能提取编号——
 * 流进行中无法校验，边流边发会出现「编号还没出现就已经发了引用」的错序。
 *
 * @param knowledgeBaseId 本轮使用的知识库标识
 * @param ragDocCount     召回分片数（通过资格过滤后的）
 * @param citations       校验通过的引用
 */
public record StreamCitations(
        Long knowledgeBaseId,
        Integer ragDocCount,
        List<RagCitation> citations) {
}
