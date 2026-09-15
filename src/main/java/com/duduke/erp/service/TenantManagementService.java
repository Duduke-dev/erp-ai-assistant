package com.duduke.erp.service;

import java.util.List;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.duduke.erp.common.exception.BusinessException;
import com.duduke.erp.entity.dto.TenantSaveDTO;
import com.duduke.erp.entity.po.Tenant;
import com.duduke.erp.entity.vo.TenantVO;
import com.duduke.erp.mapper.TenantMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 租户管理（平台视角，跨租户）。
 * <p>
 * {@code tenant} 表已在 {@code app.tenant.ignore-tables} 中，租户插件不会给它加条件——
 * 这正是平台级资源需要的语义：列出<b>所有</b>租户。
 *
 * <h3>为什么没有删除</h3>
 * 租户之下挂着业务数据、会话、用量与账目。物理删除租户会让这些数据变成
 * <b>没有归属的孤儿</b>，既无法审计也无法回收；而「删一半」比「不删」更危险。
 * 因此本服务只提供<b>启用 / 禁用</b>：{@code status=disabled} 表达「停用」，
 * 数据完整保留，需要时可恢复。
 */
@Service
@RequiredArgsConstructor
public class TenantManagementService {

    private static final String STATUS_ACTIVE = "active";

    private static final java.util.Set<String> ALLOWED_STATUS = java.util.Set.of("active", "disabled");

    private final TenantMapper tenantMapper;

    /** 全部租户，按编码排序 */
    public List<TenantVO> listTenants() {
        return this.tenantMapper.selectList(
                        Wrappers.<Tenant>lambdaQuery().orderByAsc(Tenant::getEntCode)).stream()
                .map(this::toVO).toList();
    }

    public TenantVO getTenant(Long id) {
        return toVO(requireTenant(id));
    }

    /**
     * 新建租户。
     *
     * @return 新租户主键
     */
    public Long createTenant(TenantSaveDTO dto) {
        String entCode = requireText(dto.entCode(), "租户编码不能为空");
        if (findByCode(entCode) != null) {
            throw new BusinessException("租户编码已存在：" + entCode);
        }
        Tenant tenant = new Tenant();
        tenant.setEntCode(entCode);
        tenant.setEntName(requireText(dto.entName(), "租户名称不能为空"));
        tenant.setStatus(normalizeStatus(dto.status()));
        this.tenantMapper.insert(tenant);
        return tenant.getId();
    }

    /**
     * 更新租户（名称 / 状态）。<b>不接受改编码</b>：编码是隔离键，
     * 改掉会让该租户既有数据全部「找不到」，且不会报错。
     */
    public void updateTenant(Long id, TenantSaveDTO dto) {
        Tenant tenant = requireTenant(id);
        tenant.setEntName(requireText(dto.entName(), "租户名称不能为空"));
        tenant.setStatus(normalizeStatus(dto.status()));
        this.tenantMapper.updateById(tenant);
    }

    private Tenant requireTenant(Long id) {
        Tenant tenant = this.tenantMapper.selectById(id);
        if (tenant == null) {
            throw new BusinessException("租户不存在：" + id);
        }
        return tenant;
    }

    private Tenant findByCode(String entCode) {
        return this.tenantMapper.selectOne(Wrappers.<Tenant>lambdaQuery()
                .eq(Tenant::getEntCode, entCode).last("LIMIT 1"));
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

    private TenantVO toVO(Tenant tenant) {
        return new TenantVO(tenant.getId(), tenant.getEntCode(), tenant.getEntName(),
                tenant.getStatus(), tenant.getCreatedAt());
    }

}
