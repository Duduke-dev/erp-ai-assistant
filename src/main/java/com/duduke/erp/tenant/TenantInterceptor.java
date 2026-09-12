package com.duduke.erp.tenant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.duduke.erp.component.satoken.StpInterfaceImpl;
import com.duduke.erp.common.response.Result;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.ObjectMapper;

/**
 * 租户解析拦截器。
 * <p>
 * 实现要点：
 * <ol>
 *   <li>用 Interceptor 而非 Filter —— Filter 早于 DispatcherServlet 执行，
 *       此时 Sa-Token 拿不到 Request 上下文，StpUtil 会失效。</li>
 *   <li>必须注册在 SaInterceptor 之后 —— 这样才能读到已解析的登录态。</li>
 *   <li>afterCompletion 中必须清理 —— 线程池复用不清理会导致租户串号。</li>
 * </ol>
 * 解析优先级：登录会话中的租户 &gt; 请求头。请求头仅用于内部调用与测试。
 */
@Component
@RequiredArgsConstructor
public class TenantInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    /** 租户标识请求头。 */
    public static final String ENT_CODE_HEADER = "X-Ent-Code";

    /** 用户标识请求头。 */
    public static final String USER_ID_HEADER = "X-User-Id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
            Object handler) throws Exception {
        String entCode = null;
        Long userId = null;

        if (StpUtil.isLogin()) {
            Object loginId = StpUtil.getLoginId();
            if (loginId != null) {
                userId = Long.parseLong(String.valueOf(loginId));
            }
            // 第二个参数为 false：Session 不存在时返回 null，不自动创建
            SaSession session = StpUtil.getSessionByLoginId(loginId, false);
            if (session != null) {
                entCode = session.getString(StpInterfaceImpl.ENT_CODE_KEY);
            }
        }

        if (!StringUtils.hasText(entCode)) {
            entCode = request.getHeader(ENT_CODE_HEADER);
            if (userId == null) {
                userId = parseUserId(request.getHeader(USER_ID_HEADER));
            }
        }

        if (!StringUtils.hasText(entCode)) {
            writeError(response);
            return false;
        }

        TenantContext.set(entCode.trim(), userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception ex) {
        TenantContext.clear();
    }

    /**
     * 输出与业务响应一致的结构。
     * <p>
     * 拦截器直接写响应并中断，不会经过 {@code GlobalResponseAdvice}，
     * 因此这里手动序列化 Result，避免出现另一种响应格式。
     */
    private void writeError(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(this.objectMapper.writeValueAsString(
            Result.fail(400, "无法确定租户标识，请登录后访问或携带 X-Ent-Code 请求头")));
    }

    private Long parseUserId(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

}
