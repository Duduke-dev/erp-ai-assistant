package com.duduke.erp.entity.vo;

/**
 * 数据缺失提示事件。
 * <p>
 * 与 {@code StreamError} 的区别：这是**回答本身成功、但依据可疑**的情形，
 * 不该按错误处理（用户仍能看到完整回答），因此单独一个事件类型。
 * <p>
 * 目前只有一个触发场景：问题判定为「需要业务数据」，但本轮没有取得任何非空 Tool 结果——
 * 此时模型给出的数字可能是凭印象编的。前端应把这条提示显示得**比正文更显眼**，
 * 否则用户会直接采用那些数字。
 *
 * @param message 面向用户的提示文案
 */
public record StreamWarning(String message) {
}
