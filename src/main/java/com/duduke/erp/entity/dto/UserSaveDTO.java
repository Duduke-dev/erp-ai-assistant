package com.duduke.erp.entity.dto;

/**
 * 本租户用户新增 / 更新入参。
 * <p>
 * <b>{@code password} 在更新时选填</b>：为空表示「不改密码」，避免管理端
 * 每次改个姓名都要重新设一遍密码——那样做只会让人人都用同一个简单密码。
 */
public record UserSaveDTO(
        String username,
        String password,
        String realName,
        String phone,
        String roleCode,
        String status) {
}
