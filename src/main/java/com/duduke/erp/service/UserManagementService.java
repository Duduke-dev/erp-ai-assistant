package com.duduke.erp.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duduke.erp.common.exception.BusinessException;
import com.duduke.erp.entity.dto.UserSaveDTO;
import com.duduke.erp.entity.po.SysRole;
import com.duduke.erp.entity.po.SysUser;
import com.duduke.erp.entity.po.SysUserRole;
import com.duduke.erp.entity.vo.TenantUserVO;
import com.duduke.erp.mapper.SysRoleMapper;
import com.duduke.erp.mapper.SysUserMapper;
import com.duduke.erp.mapper.SysUserRoleMapper;
import com.duduke.erp.tenant.TenantContext;

import lombok.RequiredArgsConstructor;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 本租户用户管理。
 * <p>
 * <b>范围只限于当前租户</b>：{@code sys_user} / {@code sys_role} / {@code sys_user_role}
 * 都有 {@code ent_code} 且不在 ignore-tables 中，租户插件会自动把条件注入到
 * 每一条查询与写入上。因此这里<b>一行租户代码都不写</b>，
 * 也<b>不需要</b> {@code @InterceptorIgnore}——
 * 「跨租户管理用户」等于能改别家的用户与角色，那是提权，不是本服务的语义。
 *
 * <h3>两条防自锁的约束</h3>
 * <ol>
 *   <li><b>不能删除自己</b>：管理员删掉自己就再也进不来了；</li>
 *   <li><b>不能停用自己</b>：同理，停用后当前会话还能用，但下次登录即被拒。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class UserManagementService {

    private static final String STATUS_ACTIVE = "active";

    private static final java.util.Set<String> ALLOWED_STATUS = java.util.Set.of("active", "disabled");

    private final SysUserMapper userMapper;

    private final SysRoleMapper roleMapper;

    private final SysUserRoleMapper userRoleMapper;

    private final PasswordEncoder passwordEncoder;

    /** 本租户全部用户（含角色信息） */
    public List<TenantUserVO> listUsers() {
        List<SysUser> users = this.userMapper.selectList(
                Wrappers.<SysUser>lambdaQuery().orderByAsc(SysUser::getId));
        if (users.isEmpty()) {
            return List.of();
        }
        // 批量取角色与关联，避免逐个用户查角色（N+1）
        Map<Long, SysRole> rolesById = new HashMap<>();
        for (SysRole role : this.roleMapper.selectList(Wrappers.<SysRole>lambdaQuery())) {
            rolesById.put(role.getId(), role);
        }
        Map<Long, Long> roleIdByUserId = new HashMap<>();
        for (SysUserRole link : this.userRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery())) {
            roleIdByUserId.put(link.getUserId(), link.getRoleId());
        }

        List<TenantUserVO> result = new ArrayList<>(users.size());
        for (SysUser user : users) {
            SysRole role = rolesById.get(roleIdByUserId.get(user.getId()));
            result.add(toVO(user, role));
        }
        return result;
    }

    public TenantUserVO getUser(Long id) {
        SysUser user = requireUser(id);
        return toVO(user, findRoleOf(user.getId()));
    }

    /**
     * 新建用户并分配角色。
     *
     * @return 新用户主键
     */
    @Transactional
    public Long createUser(UserSaveDTO dto) {
        String username = requireText(dto.username(), "用户名不能为空");
        if (findByUsername(username) != null) {
            throw new BusinessException("用户名已存在：" + username);
        }
        if (!StringUtils.hasText(dto.password())) {
            throw new BusinessException("新增用户必须设置初始密码");
        }
        SysRole role = requireRole(dto.roleCode());

        SysUser user = new SysUser();
        user.setUsername(username);
        // 只存散列，明文不落库、不写日志
        user.setPasswordHash(this.passwordEncoder.encode(dto.password()));
        user.setRealName(trimToNull(dto.realName()));
        user.setPhone(trimToNull(dto.phone()));
        user.setStatus(normalizeStatus(dto.status()));
        this.userMapper.insert(user);

        bindRole(user.getId(), role);
        return user.getId();
    }

    /**
     * 更新用户。<b>用户名不可改</b>（登录凭据），密码为空表示不改。
     */
    @Transactional
    public void updateUser(Long id, UserSaveDTO dto) {
        SysUser user = requireUser(id);
        if (STATUS_ACTIVE.equals(user.getStatus())
                && !STATUS_ACTIVE.equals(normalizeStatus(dto.status()))
                && isCurrentUser(id)) {
            throw new BusinessException("不能停用当前登录用户，否则下次将无法登录");
        }
        user.setRealName(trimToNull(dto.realName()));
        user.setPhone(trimToNull(dto.phone()));
        user.setStatus(normalizeStatus(dto.status()));
        if (StringUtils.hasText(dto.password())) {
            user.setPasswordHash(this.passwordEncoder.encode(dto.password()));
        }
        this.userMapper.updateById(user);

        SysRole role = requireRole(dto.roleCode());
        replaceRole(user.getId(), role);
    }

    /**
     * 删除用户，并清理其角色关联。
     * <p>
     * <b>不能删除自己</b>：管理员删掉自己就再也进不来了，
     * 而这个错误没有自助恢复的路径。
     */
    @Transactional
    public void removeUser(Long id) {
        requireUser(id);
        if (isCurrentUser(id)) {
            throw new BusinessException("不能删除当前登录用户");
        }
        this.userRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getUserId, id));
        this.userMapper.deleteById(id);
    }

    // ===== 内部 =====

    private void bindRole(Long userId, SysRole role) {
        SysUserRole link = new SysUserRole();
        link.setUserId(userId);
        link.setRoleId(role.getId());
        this.userRoleMapper.insert(link);
    }

    /** 先清旧关联再插新关联：用户只保留一个角色，避免权限叠加出意外结果 */
    private void replaceRole(Long userId, SysRole role) {
        this.userRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getUserId, userId));
        bindRole(userId, role);
    }

    private SysRole findRoleOf(Long userId) {
        SysUserRole link = this.userRoleMapper.selectOne(Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getUserId, userId).last("LIMIT 1"));
        return link == null ? null : this.roleMapper.selectById(link.getRoleId());
    }

    private SysUser requireUser(Long id) {
        SysUser user = this.userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException("用户不存在：" + id);
        }
        return user;
    }

    private SysUser findByUsername(String username) {
        return this.userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, username).last("LIMIT 1"));
    }

    private SysRole requireRole(String roleCode) {
        if (!StringUtils.hasText(roleCode)) {
            throw new BusinessException("必须指定角色");
        }
        SysRole role = this.roleMapper.selectOne(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getRoleCode, roleCode.trim()).last("LIMIT 1"));
        if (role == null) {
            throw new BusinessException("角色不存在：" + roleCode);
        }
        return role;
    }

    private boolean isCurrentUser(Long userId) {
        Long current = TenantContext.getUserId();
        return current != null && current.equals(userId);
    }

    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return STATUS_ACTIVE;
        }
        String normalized = status.trim().toLowerCase(java.util.Locale.ROOT);
        if (!ALLOWED_STATUS.contains(normalized)) {
            throw new BusinessException("状态只能是 active / disabled，实际为：" + status);
        }
        return normalized;
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(message);
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private TenantUserVO toVO(SysUser user, SysRole role) {
        return new TenantUserVO(user.getId(), user.getUsername(), user.getRealName(),
                user.getPhone(), user.getStatus(),
                role == null ? null : role.getRoleCode(),
                role == null ? null : role.getRoleName(),
                user.getCreatedAt());
    }

}
