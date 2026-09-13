package com.duduke.erp.entity.vo;

/**
 * 文本增量事件。
 *
 * @param text 本次新增的文本片段。可能为空串（上游偶发空帧），前端应忽略
 */
public record StreamDelta(String text) {
}
