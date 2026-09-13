package com.duduke.erp.entity.vo;

/**
 * 错误事件。
 * <p>
 * SSE 一旦开始就无法再改 HTTP 状态码，因此错误只能作为事件下发。
 * {@code message} 是给用户看的稳定文案，**不含原始异常信息**——
 * 异常细节只进服务端日志，避免把内部实现（SQL、路径、堆栈片段）暴露给前端。
 *
 * @param code    稳定错误码，前端可用于区分处理
 * @param message 面向用户的提示文案
 */
public record StreamError(String code, String message) {
}
