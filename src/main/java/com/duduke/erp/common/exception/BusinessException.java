package com.duduke.erp.common.exception;

import lombok.Getter;

/**
 * 业务异常。
 * <p>
 * 用于表达可以预期的业务失败（参数不合法、状态不允许、资源不存在等），
 * 由 {@code GlobalExceptionHandler} 统一转成 {@code Result} 返回。
 * 与系统异常区分开，便于日志分级与前端提示。
 */
@Getter
public class BusinessException extends RuntimeException {

    /** 业务错误码，直接作为响应体 code 返回。 */
    private final int code;

    public BusinessException(String message) {
        this(400, message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

}
