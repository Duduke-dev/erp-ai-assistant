package com.duduke.erp.service.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.tool.ToolCallback;

/**
 * 某一时刻的 Tool 注册快照。
 * <p>
 * <b>不可变</b>：请求线程用 {@code AtomicReference} 无锁读到它之后，
 * 刷新线程无论怎么重建新快照都不会影响进行中的请求。
 * 这正是「刷新不影响在跑的问答」的实现方式。
 *
 * @param version             版本号，每次刷新递增。用于日志与排查「这一轮用的是哪版工具集」
 * @param callbacks           全部 Tool（未按权限过滤）
 * @param requiredPermissions Tool 名 → 所需权限码。
 *                            每个已注册 Tool 都必须在这里有条目——注册表在构建快照时强制保证
 */
public record ToolSnapshot(
        long version,
        List<ToolCallback> callbacks,
        Map<String, String> requiredPermissions) {

    public ToolSnapshot {
        callbacks = List.copyOf(callbacks);
        requiredPermissions = Map.copyOf(requiredPermissions);
    }

    /** 启动初值：没有任何 Tool。刷新失败时保留的就是它，等于「静默降级为无工具」 */
    public static ToolSnapshot empty() {
        return new ToolSnapshot(0, List.of(), Map.of());
    }

    public int size() {
        return this.callbacks.size();
    }

    /**
     * 取某个用户有权使用的 Tool。
     * <p>
     * <b>fail-closed</b>：若某个 Tool 在 {@code requiredPermissions} 里没有条目，
     * 视为不可见，而不是放行。漏声明权限的后果必须是「用不了」，
     * 不能是「谁都能用」。
     *
     * @param permissions 当前用户的权限码集合
     */
    public List<ToolCallback> visibleTo(Set<String> permissions) {
        List<ToolCallback> visible = new ArrayList<>();
        for (ToolCallback callback : this.callbacks) {
            String required = this.requiredPermissions.get(callback.getToolDefinition().name());
            if (required != null && permissions.contains(required)) {
                visible.add(callback);
            }
        }
        return List.copyOf(visible);
    }

}
