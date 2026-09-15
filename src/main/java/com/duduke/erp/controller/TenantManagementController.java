package com.duduke.erp.controller;

import java.util.List;

import com.duduke.erp.entity.dto.TenantSaveDTO;
import com.duduke.erp.entity.vo.TenantVO;
import com.duduke.erp.service.TenantManagementService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户管理接口（平台视角）。
 * <p>
 * 遵循手册「前后端规约」：资源名词复数、不带 {@code /page}、
 * POST 新建 / PUT 更新 / GET 查询。
 * <p>
 * <b>没有 DELETE</b>：租户下挂着业务数据与账目，物理删除会留下无法审计的孤儿数据。
 * 停用请用 {@code PUT} 把 {@code status} 置为 {@code disabled}。
 * <p>
 * 权限：读 {@code platform:tenant:list}，写 {@code platform:tenant:manage}，
 * 均<b>仅 admin</b>（V13 授予）——跨租户可见性不该随普通账号扩散。
 */
@RestController
@RequestMapping("/api/platform/tenants")
@RequiredArgsConstructor
public class TenantManagementController {

    private final TenantManagementService tenantManagementService;

    @SaCheckPermission("platform:tenant:list")
    @GetMapping
    public List<TenantVO> listTenants() {
        return this.tenantManagementService.listTenants();
    }

    @SaCheckPermission("platform:tenant:list")
    @GetMapping("/{id}")
    public TenantVO getTenant(@PathVariable Long id) {
        return this.tenantManagementService.getTenant(id);
    }

    @SaCheckPermission("platform:tenant:manage")
    @PostMapping
    public Long createTenant(@RequestBody TenantSaveDTO dto) {
        return this.tenantManagementService.createTenant(dto);
    }

    /** 更新；编码不可改，停用/启用通过 status 表达 */
    @SaCheckPermission("platform:tenant:manage")
    @PutMapping("/{id}")
    public void updateTenant(@PathVariable Long id, @RequestBody TenantSaveDTO dto) {
        this.tenantManagementService.updateTenant(id, dto);
    }

}
