package com.duduke.erp.common.response;

import java.io.Serializable;

/**
 * 统一响应封装。
 * <p>
 * 业务代码直接返回业务对象即可，由 {@code GlobalResponseAdvice} 自动包装成本类型，
 * 不需要在 Controller 里手写 {@code Result.ok(...)}。
 *
 * @param <T> 数据类型
 */
public record Result<T>(int code, String message, T data) implements Serializable {

    /** 成功状态码。 */
    public static final int SUCCESS_CODE = 200;

    public static <T> Result<T> ok(T data) {
        return new Result<>(SUCCESS_CODE, "ok", data);
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    public static <T> Result<T> fail(String message) {
        return fail(500, message);
    }

}
