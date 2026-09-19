package com.duduke.erp.entity.vo;

/**
 * 错误事件。
 * <p>
 * SSE 一旦开始就无法再改 HTTP 状态码，因此错误只能作为事件下发。
 * {@code message} 是给用户看的稳定文案，**不含原始异常信息**——
 * 异常细节只进服务端日志，避免把内部实现（SQL、路径、堆栈片段）暴露给前端。
 * <p>
 * {@code detail} 是<b>可选</b>的开发期诊断信息（原始异常摘要），由
 * {@code app.chat.expose-error-detail} 控制，<b>默认关闭</b>：
 * 原始异常可能带表名、SQL、内网地址、文件路径，生产环境返回它是安全风险；
 * 但开发期拿不到细节时，「前端一条通用提示 + 服务端一堆日志」很难对上账。
 * 所以做成「默认安全 + 本地可开」的开关，而不是二选一。
 *
 * @param code    稳定错误码，前端可用于区分处理
 * @param message 面向用户的提示文案
 * @param detail  原始异常摘要；仅开关打开时填充，否则为 {@code null}
 */
public record StreamError(String code, String message, String detail) {
}
