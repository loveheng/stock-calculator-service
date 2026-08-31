package com.zzh.stock_calculator.controller;

import com.zzh.stock_calculator.common.ApiResponse;
import com.zzh.stock_calculator.common.AuthErrorCode;
import com.zzh.stock_calculator.common.ProfileConflictException;
import com.zzh.stock_calculator.dto.auth.AuthSessionResponse;
import com.zzh.stock_calculator.dto.auth.LoginRequest;
import com.zzh.stock_calculator.dto.auth.ProfileResponse;
import com.zzh.stock_calculator.dto.auth.ProfileUpsertRequest;
import com.zzh.stock_calculator.dto.auth.RecoveryConfirmRequest;
import com.zzh.stock_calculator.dto.auth.RecoveryEmailRequest;
import com.zzh.stock_calculator.dto.auth.RecoveryVerifyRequest;
import com.zzh.stock_calculator.dto.auth.RegisterRequest;
import com.zzh.stock_calculator.service.AuthService;
import com.zzh.stock_calculator.service.ProfileService;
import com.zzh.stock_calculator.service.RateLimitService;
import com.zzh.stock_calculator.util.AuthCryptoUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * E2EE 用户服务 8 端点（docs/e2ee-auth-backend-design.md §D.4.2）。
 *
 * @description 响应遵循项目 ApiResponse 信封，HTTP 恒 200（决策 B8），唯一例外：
 *              AuthInterceptor 对未认证请求写 HTTP 401 + 信封体。
 *              零知识红线：本控制器日志不输出 password / token / 验证码。
 *              限流（《设计》§D.5.4）在 RateLimitService 完成：IP 维度取 X-Forwarded-For 首跳
 *              （经 Vercel 代理，可伪造边界已记录为 P2 取舍）。
 */
@CrossOrigin(origins = "*") // 允许前端直接跨域调用（对齐 ImportController 先例；Bearer 无 cookie，无 CSRF 面）
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true")
public class AuthController {

    private final AuthService authService;
    private final ProfileService profileService;
    private final RateLimitService rateLimitService;

    /** 端点 1：注册即登录；不接收任何密文（D9 不变量） */
    @PostMapping("/register")
    public ApiResponse<AuthSessionResponse> register(@RequestBody RegisterRequest request,
                                                     HttpServletRequest httpRequest) {
        rateLimitService.checkRegister(ipOf(httpRequest));
        return ApiResponse.success(authService.register(request));
    }

    /** 端点 2：登录；hasProfile 驱动前端缺行分支（孤儿引导 / 补传） */
    @PostMapping("/login")
    public ApiResponse<AuthSessionResponse> login(@RequestBody LoginRequest request,
                                                  HttpServletRequest httpRequest) {
        String email = AuthCryptoUtil.normalizeEmail(request == null ? null : request.getEmail());
        rateLimitService.checkLogin(ipOf(httpRequest), email);
        return ApiResponse.success(authService.login(request));
    }

    /** 端点 3：登出；吊销当前会话（幂等） */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestAttribute("authTokenHash") String tokenHash) {
        authService.logout(tokenHash);
        return ApiResponse.success(null);
    }

    /** 端点 4：读密文档案；缺行 404（前端合法中间态） */
    @GetMapping("/profile")
    public ApiResponse<ProfileResponse> getProfile(@RequestAttribute("authUserId") UUID userId) {
        return ApiResponse.success(profileService.get(userId));
    }

    /** 端点 5：upsert 密文档案；If-Match 不符 → 409 + data.updatedAt（决策 B5） */
    @PutMapping("/profile")
    public ApiResponse<?> upsertProfile(@RequestAttribute("authUserId") UUID userId,
                                        @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                        @RequestBody ProfileUpsertRequest request) {
        try {
            return ApiResponse.success(profileService.upsert(userId, request, ifMatch));
        } catch (ProfileConflictException e) {
            return ApiResponse.<Map<String, String>>builder()
                    .code(AuthErrorCode.CONFLICT)
                    .message(e.getMessage())
                    .data(Map.of("updatedAt", e.getServerUpdatedAt()))
                    .build();
        }
    }

    /** 端点 6：请求找回验证码；响应恒 200，不泄露邮箱存在性（决策 B7 找回侧） */
    @PostMapping("/recovery/request")
    public ApiResponse<Void> requestRecovery(@RequestBody RecoveryEmailRequest request,
                                             HttpServletRequest httpRequest) {
        String email = AuthCryptoUtil.normalizeEmail(request == null ? null : request.getEmail());
        rateLimitService.checkRecoveryRequest(ipOf(httpRequest), email);
        authService.requestRecovery(email);
        return ApiResponse.success(null);
    }

    /** 端点 7：校验验证码 → recovery 受限会话（《前端 spec》§6.5 步骤 3） */
    @PostMapping("/recovery/verify")
    public ApiResponse<AuthSessionResponse> verifyRecovery(@RequestBody RecoveryVerifyRequest request,
                                                           HttpServletRequest httpRequest) {
        String email = AuthCryptoUtil.normalizeEmail(request == null ? null : request.getEmail());
        rateLimitService.checkVerify(ipOf(httpRequest), email);
        return ApiResponse.success(authService.verifyRecovery(email, request.getCode()));
    }

    /** 端点 8：原子改密（《前端 spec》§6.5 步骤 5-8，决策 B6）→ 全量新会话 */
    @PostMapping("/recovery/confirm")
    public ApiResponse<AuthSessionResponse> confirmRecovery(@RequestAttribute("authUserId") UUID userId,
                                                            @RequestAttribute("authTokenHash") String tokenHash,
                                                            @RequestBody RecoveryConfirmRequest request) {
        return ApiResponse.success(authService.confirmRecovery(userId, tokenHash, request));
    }

    private String ipOf(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        return xff == null || xff.isBlank() ? request.getRemoteAddr() : xff.split(",")[0].trim();
    }
}
