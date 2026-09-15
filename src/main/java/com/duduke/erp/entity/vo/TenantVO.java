package com.duduke.erp.entity.vo;

import java.time.LocalDateTime;

/**
 * 租户展示对象。
 * <p>
 * 这是<b>平台视角</b>的展示：跨租户列出所有租户，因此不能复用任何
 * 「本租户视角」的 VO。
 */
public record TenantVO(
        Long id,
        String entCode,
        String entName,
        String status,
        LocalDateTime createdAt) {
}
