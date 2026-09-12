package com.duduke.erp.entity.vo;

import java.util.List;

/**
 * 登录结果的展示对象。
 * <p>
 * 返回体最终形如 {@code Result<LoginVO>}：{@code Result} 是统一响应信封，
 * 本类型是信封内的业务数据。
 */
public record LoginVO(String token, Long userId, String entCode, String username,
                      String realName, List<String> roles, List<String> permissions) {
}
