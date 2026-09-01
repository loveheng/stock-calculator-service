package com.zzh.stock_calculator.auth.config;
import com.zzh.stock_calculator.common.ApiResponse;
import com.zzh.stock_calculator.common.AuthErrorCode;
import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.auth.entity.AuthSessionEntity;
import com.zzh.stock_calculator.auth.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import tools.jackson.databind.ObjectMapper;

/**
 * Bearer 会话拦截（docs/e2ee-auth-backend-design.md §D.2.2）。
 *
 * @description 解析 → SessionService.resolve → 注入 authUserId / authScope / authTokenHash；
 *              recovery 受限会话仅放行 recovery/confirm 与 logout（§D.4.3）；
 *              失败写 HTTP 401 + ApiResponse 信封体（决策 B8 唯一例外）。
 *              仅挂受保护路径（WebConfig）：register / login / recovery request+verify 无会话，不经过本拦截器。
 *              ObjectMapper 注入 Boot 4 自动装配的 Jackson 3（tools.jackson）Bean。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true")
public class AuthInterceptor implements HandlerInterceptor {

    private final SessionService sessionService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            return reject(response);
        }
        AuthSessionEntity session;
        try {
            session = sessionService.resolve(authorization.substring(7).trim());
        } catch (BusinessException e) {
            return reject(response);
        }
        String uri = request.getRequestURI();
        boolean recoveryScopeAllowed = uri.endsWith("/api/auth/recovery/confirm")
                || uri.endsWith("/api/auth/logout");
        if (SessionService.SCOPE_RECOVERY.equals(session.getScope()) && !recoveryScopeAllowed) {
            return reject(response);
        }
        request.setAttribute("authUserId", session.getUserId());
        request.setAttribute("authScope", session.getScope());
        request.setAttribute("authTokenHash", session.getTokenHash());
        return true;
    }

    private boolean reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.fail(AuthErrorCode.UNAUTHORIZED, "会话已失效，请重新登录")));
        return false;
    }
}
