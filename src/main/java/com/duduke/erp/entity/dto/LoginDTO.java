package com.duduke.erp.entity.dto;

/**
 * 登录请求（数据传输对象）。
 * <p>
 * entCode 必填，因为用户名只在租户内唯一。
 */
public record LoginDTO(String entCode, String username, String password) {
}
