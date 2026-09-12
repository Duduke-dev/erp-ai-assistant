package com.duduke.erp.component.satoken;

import java.util.List;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Component;

/**
 * Sa-Token 权限与角色数据源。
 * <p>
 * 权限码在登录时写入 Sa-Token 的 User-Session，这里直接读取，
 * 避免每次鉴权都回查数据库，也避免 StpInterface 调用时租户上下文可能缺失的问题。
 */
@Component
public class StpInterfaceImpl implements StpInterface {

    /** Session 中权限码列表的键。 */
    public static final String PERMISSION_KEY = "permissions";

    /** Session 中角色码列表的键。 */
    public static final String ROLE_KEY = "roles";

    /** Session 中租户标识的键。 */
    public static final String ENT_CODE_KEY = "entCode";

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return readStringList(loginId, PERMISSION_KEY);
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return readStringList(loginId, ROLE_KEY);
    }

    private List<String> readStringList(Object loginId, String key) {
        // 第二个参数为 false：Session 不存在时返回 null 而不是自动创建
        SaSession session = StpUtil.getSessionByLoginId(loginId, false);
        if (session == null) {
            return List.of();
        }
        Object value = session.get(key);
        if (value instanceof List<?> list) {
            return list.stream().filter(item -> item instanceof String)
                .map(item -> (String) item)
                .toList();
        }
        return List.of();
    }

}
