package com.duduke.erp.controller;

import com.duduke.erp.entity.dto.LoginDTO;
import com.duduke.erp.entity.vo.LoginVO;
import com.duduke.erp.entity.vo.UserVO;
import com.duduke.erp.service.AuthService;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 * <p>
 * 返回值直接是业务对象，统一由 {@code GlobalResponseAdvice} 包装成 Result，
 * 这里不需要出现任何响应封装代码。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 登录。
     *
     * @param request 登录请求
     * @return 令牌与用户信息
     */
    @PostMapping("/login")
    public LoginVO login(@RequestBody LoginDTO request) {
        return this.authService.login(request);
    }

    /**
     * 登出。
     */
    @PostMapping("/logout")
    public void logout() {
        this.authService.logout();
    }

    /**
     * 当前登录用户信息。
     *
     * @return 用户信息
     */
    @GetMapping("/current")
    public UserVO current() {
        return this.authService.current();
    }

    /**
     * 注解鉴权回归探针。
     * <p>
     * 这个接口没有业务含义，存在的唯一目的是验证 {@code @SaCheckPermission} 真的生效：
     * Sa-Token 的注解鉴权依赖 SaInterceptor 驱动，一旦拦截器注册被误删，
     * 注解会被静默忽略（无权限也放行）。M1.6 的测试用这个接口做回归，
     * 缺失权限时必须返回 403，否则说明鉴权链路已失效。
     * <p>
     * 返回 String 还顺带覆盖了统一包装的 String 分支（最容易抛 ClassCastException 的路径）。
     *
     * @return 固定标识文本
     */
    @SaCheckPermission("biz:product:list")
    @GetMapping("/permission-probe")
    public String permissionProbe() {
        return "permission-ok";
    }

}
