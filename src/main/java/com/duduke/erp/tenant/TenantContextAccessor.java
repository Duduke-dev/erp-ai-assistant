package com.duduke.erp.tenant;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * 租户上下文的 ThreadLocalAccessor。
 * <p>
 * 注册到 Micrometer {@code ContextRegistry} 后，Reactor 线程切换与线程池切换
 * 都能自动保存和恢复租户标识，避免异步链路丢失租户导致越权或空指针。
 * <p>
 * <b>租户与用户必须一起传播</b>：只带 {@code ent_code} 会让下游线程上的
 * {@code userId} 凭空消失，而流式收口、消息落库等环节都可能需要它。
 * 早期版本只传了租户标识，属于隐性缺陷——单请求同步链路不会暴露，
 * 一旦换到线程池或 Reactor 线程就会丢。
 */
public class TenantContextAccessor implements ThreadLocalAccessor<TenantContextAccessor.TenantSnapshot> {

    /** 上下文中租户信息的键名。 */
    public static final String KEY = "tenant.context";

    /**
     * 传播用的不可变快照。
     * <p>
     * 两个字段一起传，避免「只恢复租户、丢掉用户」的半残状态。
     */
    public record TenantSnapshot(String entCode, Long userId) {
    }

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public TenantSnapshot getValue() {
        String entCode = TenantContext.getEntCode();
        Long userId = TenantContext.getUserId();
        if (entCode == null && userId == null) {
            return null;
        }
        return new TenantSnapshot(entCode, userId);
    }

    @Override
    public void setValue(TenantSnapshot value) {
        if (value == null) {
            TenantContext.clear();
            return;
        }
        TenantContext.set(value.entCode(), value.userId());
    }

    @Override
    public void reset() {
        TenantContext.clear();
    }

}
