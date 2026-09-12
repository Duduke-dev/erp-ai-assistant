package com.duduke.erp.component.satoken;

import com.duduke.erp.tenant.TenantInterceptor;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 拦截器配置。
 * <p>
 * 关键：Sa-Token 的注解鉴权（{@code @SaCheckPermission} / {@code @SaCheckRole}）
 * 依赖 SaInterceptor 驱动。不注册这个拦截器时，注解会被静默忽略——
 * 有权限放行、没权限也放行，且不抛任何异常。这是最容易漏掉且后果最严重的坑。
 * <p>
 * M1.6 的测试里保留了「有 Token 但无权限码必须 403」的反向用例做回归，
 * 一旦有人误删这里的注册，测试会立刻失败。
 */
@Configuration
@RequiredArgsConstructor
public class SaTokenConfig implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 顺序敏感：先注册 Sa-Token 解析登录态，再注册租户解析依赖该登录态。
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
            .addPathPatterns("/api/**")
            .excludePathPatterns(
                "/api/auth/login",
                "/api/auth/captcha"
            );

        // 租户解析。登录接口此时尚未建立会话，租户由请求体提供，故一并排除。
        registry.addInterceptor(this.tenantInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns("/api/auth/login");
    }

}
