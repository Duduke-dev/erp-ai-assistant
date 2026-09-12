package com.duduke.erp.service;

import java.util.Arrays;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.duduke.erp.component.satoken.StpInterfaceImpl;
import com.duduke.erp.entity.dto.LoginDTO;
import com.duduke.erp.entity.po.SysRole;
import com.duduke.erp.entity.po.SysUser;
import com.duduke.erp.entity.po.Tenant;
import com.duduke.erp.entity.vo.LoginVO;
import com.duduke.erp.entity.vo.UserVO;
import com.duduke.erp.mapper.SysRoleMapper;
import com.duduke.erp.mapper.SysUserMapper;
import com.duduke.erp.mapper.TenantMapper;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 认证服务。
 * <p>
 * 登录成功后把租户、角色、权限写入 Sa-Token 的 User-Session，
 * 之后鉴权与租户解析都从 Session 读取，不再回查数据库。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;

    private final SysRoleMapper roleMapper;

    private final TenantMapper tenantMapper;

    private final PasswordEncoder passwordEncoder;

    /**
     * 登录。
     *
     * @param request 登录请求
     * @return 令牌与用户信息
     */
    public LoginVO login(LoginDTO request) {
        String entCode = request.entCode() == null ? "" : request.entCode().trim();
        String username = request.username() == null ? "" : request.username().trim();
        if (!StringUtils.hasText(entCode) || !StringUtils.hasText(username)
                || !StringUtils.hasText(request.password())) {
            throw new IllegalArgumentException("租户标识、用户名和密码均不能为空");
        }
        if (!this.isTenantActive(entCode)) {
            throw new IllegalArgumentException("租户不存在或已停用");
        }

        SysUser user = this.userMapper.selectForLogin(entCode, username);
        if (user == null || !this.passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // 用户不存在与密码错误返回同一提示，避免暴露账号是否注册
            throw new IllegalArgumentException("用户名或密码错误");
        }
        if (!"active".equals(user.getStatus())) {
            throw new IllegalArgumentException("账号已停用");
        }

        List<SysRole> roles = this.roleMapper.selectRolesByUserId(user.getId(), entCode);
        List<String> roleCodes = roles.stream().map(SysRole::getRoleCode).toList();
        List<String> permissions = roles.stream()
            .map(SysRole::getPermissions)
            .filter(StringUtils::hasText)
            .flatMap(value -> Arrays.stream(value.split(",")))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .distinct()
            .toList();

        StpUtil.login(user.getId());
        SaSession session = StpUtil.getSession();
        session.set(StpInterfaceImpl.ENT_CODE_KEY, entCode);
        session.set(StpInterfaceImpl.ROLE_KEY, roleCodes);
        session.set(StpInterfaceImpl.PERMISSION_KEY, permissions);

        return new LoginVO(
            StpUtil.getTokenValue(),
            user.getId(),
            entCode,
            user.getUsername(),
            user.getRealName(),
            roleCodes,
            permissions);
    }

    /**
     * 登出当前会话。
     */
    public void logout() {
        if (StpUtil.isLogin()) {
            StpUtil.logout();
        }
    }

    /**
     * 读取当前登录用户信息。
     *
     * @return 用户信息
     */
    public UserVO current() {
        Object loginId = StpUtil.getLoginId();
        long userId = Long.parseLong(String.valueOf(loginId));
        SaSession session = StpUtil.getSession();
        String entCode = session.getString(StpInterfaceImpl.ENT_CODE_KEY);
        List<String> roles = readList(session, StpInterfaceImpl.ROLE_KEY);
        List<String> permissions = readList(session, StpInterfaceImpl.PERMISSION_KEY);
        SysUser user = this.userMapper.selectById(userId);
        return new UserVO(
            userId,
            entCode,
            user == null ? null : user.getUsername(),
            user == null ? null : user.getRealName(),
            roles,
            permissions);
    }

    private boolean isTenantActive(String entCode) {
        return this.tenantMapper.selectCount(
            new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getEntCode, entCode)
                .eq(Tenant::getStatus, "active")) == 1;
    }

    @SuppressWarnings("unchecked")
    private List<String> readList(SaSession session, String key) {
        Object value = session.get(key);
        if (value instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }

}
