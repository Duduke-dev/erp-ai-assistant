package com.duduke.erp.entity.vo;

import java.time.LocalDateTime;

/**
 * 本租户用户（管理端）展示对象。
 * <p>
 * 与 {@link UserVO} 的区别：{@code UserVO} 描述的是<b>当前登录者自己</b>
 * （含自己的角色与权限），而本对象是<b>管理员视角下的在册用户列表</b>——
 * 两者字段与用途都不同，因此不复用同一个类。
 * <p>
 * <b>刻意不含 {@code passwordHash}</b>：密码散列即使不可逆也不该出网——
 * 一旦泄漏就等于给离线爆破提供了目标。不放进来，就不存在漏放的风险。
 *
 * @param roleCode 角色编码，未分配角色时为 null
 */
public record TenantUserVO(
        Long id,
        String username,
        String realName,
        String phone,
        String status,
        String roleCode,
        String roleName,
        LocalDateTime createdAt) {
}
