package com.duduke.erp.tenant;

import com.duduke.erp.component.context.ContextPropagationConfig;

/**
 * 租户上下文。
 * <p>
 * 使用 ThreadLocal 存储当前请求的租户标识与用户标识。异步与响应式场景通过
 * {@link ContextPropagationConfig} 注册的 Micrometer 上下文传播自动恢复。
 */
public final class TenantContext {

    private static final ThreadLocal<String> ENT_CODE = new ThreadLocal<>();

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(String entCode, Long userId) {
        ENT_CODE.set(entCode);
        if (userId != null) {
            USER_ID.set(userId);
        }
    }

    public static String getEntCode() {
        return ENT_CODE.get();
    }

    public static Long getUserId() {
        return USER_ID.get();
    }

    /**
     * 读取当前租户标识，缺失时抛异常。
     *
     * @return 租户标识
     * @throws IllegalStateException 当前上下文没有租户标识
     */
    public static String requireEntCode() {
        String entCode = ENT_CODE.get();
        if (entCode == null || entCode.isBlank()) {
            throw new IllegalStateException("当前上下文缺少租户标识");
        }
        return entCode;
    }

    /**
     * 清理当前线程的租户上下文。必须在请求结束的 finally 中调用，
     * 否则线程池复用会导致租户串号。
     */
    public static void clear() {
        ENT_CODE.remove();
        USER_ID.remove();
    }

}
