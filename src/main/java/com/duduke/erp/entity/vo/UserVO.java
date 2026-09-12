package com.duduke.erp.entity.vo;

import java.util.List;

/**
 * 当前登录用户的展示对象。
 */
public record UserVO(Long userId, String entCode, String username, String realName,
                     List<String> roles, List<String> permissions) {
}
