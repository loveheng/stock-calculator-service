package com.zzh.stock_calculator.orchestration.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 服务级通行证校验（步 5 定案 1，实现文档 §四点一）：编排器不对用户做复杂鉴权，只验
 * 后端持证转发的静态 Bearer token；验不过直接 401。用户级鉴权/会话/权限全收敛在 main。
 * <p>token 为空（本地开发未配置）时放行——边界防御随配置启用，避免无 DB 环境起不来。
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class PassportFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    /** W3C traceparent（步 6-0 透传源）：main 经 MCP 请求头下传，工具面透传沿用 */
    public static final String TRACEPARENT_HEADER = "traceparent";

    @Value("${orchestration.security.passport-token:}")
    private String passportToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            captureTraceId(request);
            if (passportToken == null || passportToken.isBlank()) {
                filterChain.doFilter(request, response);
                return;
            }
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith(BEARER_PREFIX)
                    && passportToken.equals(auth.substring(BEARER_PREFIX.length()))) {
                filterChain.doFilter(request, response);
                return;
            }
            log.warn("[orchestration] 通行证校验失败: {} {}", request.getMethod(), request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        } finally {
            // 请求线程归还前清理，防线程池串号
            TraceIdHolder.clear();
        }
    }

    /** 捕获 main 下传的 W3C traceparent，取 trace-id 段入 ThreadLocal（非法则不设，工具面本地生成兜底） */
    private void captureTraceId(HttpServletRequest request) {
        String traceparent = request.getHeader(TRACEPARENT_HEADER);
        if (traceparent == null || traceparent.isBlank()) {
            return;
        }
        String[] parts = traceparent.trim().split("-");
        // W3C 结构粗校验：00-<32hex trace-id>-<16hex span-id>-01，trace-id 非 0
        if (parts.length == 4 && parts[1].length() == 32 && parts[2].length() == 16
                && !parts[1].chars().allMatch(c -> c == '0')) {
            TraceIdHolder.set(parts[1]);
        }
    }
}
