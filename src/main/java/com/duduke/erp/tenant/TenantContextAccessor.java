package com.duduke.erp.tenant;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * 租户上下文的 ThreadLocalAccessor。
 * <p>
 * 注册到 Micrometer {@code ContextRegistry} 后，Reactor 线程切换与线程池切换
 * 都能自动保存和恢复租户标识，避免异步链路丢失租户导致越权或空指针。
 */
public class TenantContextAccessor implements ThreadLocalAccessor<String> {

    /** 上下文中租户标识的键名。 */
    public static final String KEY = "ent_code";

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public String getValue() {
        return TenantContext.getEntCode();
    }

    @Override
    public void setValue(String value) {
        if (value == null) {
            TenantContext.clear();
        }
        else {
            TenantContext.set(value, null);
        }
    }

    @Override
    public void reset() {
        TenantContext.clear();
    }

}
