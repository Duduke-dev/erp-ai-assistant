package com.duduke.erp.controller;

import java.util.List;

import com.duduke.erp.entity.dto.UserSaveDTO;
import com.duduke.erp.entity.vo.TenantUserVO;
import com.duduke.erp.service.UserManagementService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本租户用户管理接口。
 * <p>
 * 遵循手册「前后端规约」：资源名词复数、POST 新建 / PUT 更新 / DELETE 删除 / GET 查询。
 * <p>
 * <b>范围是本租户</b>：用户表带 {@code ent_code}，租户插件自动限定范围，
 * 因此同一个路径在不同租户下各自看到自己的用户，不需要也无法跨租户。
 * <p>
 * 权限 {@code user:list} / {@code user:save}（V14 仅授 admin）——
 * 能新建用户与分配角色等于能授予权限，属提权面。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserManagementController {

    private final UserManagementService userManagementService;

    @SaCheckPermission("user:list")
    @GetMapping
    public List<TenantUserVO> listUsers() {
        return this.userManagementService.listUsers();
    }

    @SaCheckPermission("user:list")
    @GetMapping("/{id}")
    public TenantUserVO getUser(@PathVariable Long id) {
        return this.userManagementService.getUser(id);
    }

    @SaCheckPermission("user:save")
    @PostMapping
    public Long createUser(@RequestBody UserSaveDTO dto) {
        return this.userManagementService.createUser(dto);
    }

    /** 更新；用户名不可改，密码为空表示不改密码 */
    @SaCheckPermission("user:save")
    @PutMapping("/{id}")
    public void updateUser(@PathVariable Long id, @RequestBody UserSaveDTO dto) {
        this.userManagementService.updateUser(id, dto);
    }

    @SaCheckPermission("user:save")
    @DeleteMapping("/{id}")
    public void removeUser(@PathVariable Long id) {
        this.userManagementService.removeUser(id);
    }

}
