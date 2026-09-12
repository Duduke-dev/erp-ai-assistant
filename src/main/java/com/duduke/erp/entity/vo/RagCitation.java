package com.duduke.erp.entity.vo;

/**
 * 知识库引用。
 * <p>
 * {@code index} 与回答正文中的 {@code [n]} 编号对应，由上下文拼装阶段写入文档元数据，
 * 校验阶段沿用同一套编号，保证不会错位。
 *
 * @param index   引用编号
 * @param source  来源文件名
 * @param excerpt 证据摘要（按 Unicode 码点截断）
 */
public record RagCitation(
        Integer index,
        String source,
        String excerpt) {
}
