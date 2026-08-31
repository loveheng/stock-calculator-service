package com.zzh.stock_calculator.service;

import com.zzh.stock_calculator.common.AuthErrorCode;
import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.config.AuthProperties;
import com.zzh.stock_calculator.dto.auth.AuthSessionResponse;
import com.zzh.stock_calculator.dto.auth.LoginRequest;
import com.zzh.stock_calculator.dto.auth.RecoveryConfirmRequest;
import com.zzh.stock_calculator.dto.auth.RegisterRequest;
import com.zzh.stock_calculator.entity.UserEntity;
import com.zzh.stock_calculator.repository.UserProfileRepository;
import com.zzh.stock_calculator.repository.UserRepository;
import com.zzh.stock_calculator.util.AuthCryptoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 注册 / 登录 / 登出 / 找回（docs/e2ee-auth-backend-design.md §D.4.2）。
 *
 * @description 零知识红线（决策 B2）：password 即前端 authHash（64 位 hex），落库前 bcrypt(10)，
 *              任何日志不得输出 password；用户不存在时登录路径跑 dummy bcrypt 抹平时序（§D.5.1）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true")
public class AuthService {

    /** 任意合法 bcrypt 串：用户不存在时跑一次 matches 抹平响应时间差（不对应任何真实用户） */
    private static final String DUMMY_BCRYPT =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final SessionService sessionService;
    private final OtpService otpService;
    private final AuthProperties authProperties;

    /** cost=10（《设计》§D.5.1）；authHash 本身高熵，bcrypt 防 pass-the-hash 而非离线爆破 */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);

    /**
     * 注册（《前端 spec》§6.2 步骤 A，等价 signUp 返回 Session）：注册即登录。
     *
     * @description 不接收任何密文（D9 不变量：抽查通过前密文不得上传）；
     *              邮箱已注册 → 409（决策 B7 保留枚举文案）。
     */
    @Transactional
    public AuthSessionResponse register(RegisterRequest request) {
        String email = requireValidEmail(request.getEmail());
        requireAuthHash(request.getPassword());
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(AuthErrorCode.CONFLICT, "该邮箱已注册，请直接登录");
        }
        UserEntity user = UserEntity.builder()
                .id(UUID.randomUUID())
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .build();
        userRepository.save(user);
        log.info("user registered, userId={}", user.getId());
        return sessionService.issueFull(user.getId(), authProperties.getSessionTtlDays());
    }

    /**
     * 登录（《前端 spec》§6.3）：hasProfile 驱动前端缺行分支（孤儿引导 / 补传）。
     *
     * @description 邮箱或密码错误统一 400、统一文案，不区分两种失败（《前端 spec》§8）。
     */
    @Transactional
    public AuthSessionResponse login(LoginRequest request) {
        String email = requireValidEmail(request.getEmail());
        requireAuthHash(request.getPassword());
        UserEntity user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.getPassword(), DUMMY_BCRYPT);
            throw new BusinessException(400, "邮箱或主密码错误");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(400, "邮箱或主密码错误");
        }
        int ttlDays = request.getTtlDays() == null ? authProperties.getSessionTtlDays() : request.getTtlDays();
        AuthSessionResponse session = sessionService.issueFull(user.getId(), ttlDays);
        session.setHasProfile(userProfileRepository.existsById(user.getId()));
        return session;
    }

    /** 登出（《前端 spec》§6.6）：吊销当前会话，幂等 */
    public void logout(String tokenHash) {
        sessionService.revokeByTokenHash(tokenHash);
    }

    /**
     * 找回第一步：发验证码（《前端 spec》§7.5 Step 1，等价 resetPasswordForEmail）。
     *
     * @description 恒不泄露邮箱存在性（决策 B7 找回侧）：未知邮箱静默跳过，仅记域名供排查。
     */
    public void requestRecovery(String email) {
        String normalized = AuthCryptoUtil.normalizeEmail(email);
        if (!AuthCryptoUtil.EMAIL.matcher(normalized).matches()) {
            return;
        }
        if (userRepository.findByEmail(normalized).isEmpty()) {
            log.info("recovery requested for unknown email, domain={}", domainOf(normalized));
            return;
        }
        otpService.issue(normalized);
    }

    /** 找回第二步：校验验证码 → 签发 recovery 受限会话（《前端 spec》§6.5 步骤 3，等价 verifyOtp）。
     *
     * @description 不加 @Transactional：verify 的失败计数需独立提交；签发会话由 SessionService 自带事务，
     *              消费码与签发会话分离（签发失败需重新申请验证码，单次消费语义可接受）。
     */
    public AuthSessionResponse verifyRecovery(String email, String code) {
        UserEntity user = otpService.verify(email, code);
        return sessionService.issueRecovery(user.getId());
    }

    /**
     * 找回第三步（《前端 spec》§6.5 步骤 5-8，决策 B6 原子化）：
     * 单事务内 bcrypt 新 authHash + 更新 password_payload + 吊销他端 + 签发全量新会话。
     *
     * @description recovery_payload 不变（助记词未更换）；profile 缺行时跳过密文更新
     *              （缺行是合法中间态，前端随后走孤儿引导重建，避免造出仅含半份密文的档案行）。
     *              若事务回滚，签发的会话行一并回滚，客户端持有的 token 自然失效，无悬挂状态。
     */
    @Transactional
    public AuthSessionResponse confirmRecovery(UUID userId, String currentTokenHash, RecoveryConfirmRequest request) {
        requireAuthHash(request.getNewPassword());
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.UNAUTHORIZED, "会话已失效，请重新登录"));
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        userProfileRepository.findById(userId).ifPresent(profile -> {
            profile.setPasswordPayload(request.getPasswordPayload());
            profile.setPasswordIv(request.getPasswordIv());
            userProfileRepository.save(profile); // @UpdateTimestamp 自动前移 updatedAt（If-Match 版本）
        });
        int revoked = sessionService.revokeAllOthers(userId, currentTokenHash);
        log.info("password reset applied, userId={}, revokedSessions={}", userId, revoked);
        return sessionService.issueFull(userId, authProperties.getSessionTtlDays());
    }

    // ---- 入参校验（统一中文文案，前端按 code 分支） ----

    private String requireValidEmail(String email) {
        String normalized = AuthCryptoUtil.normalizeEmail(email);
        if (normalized.isEmpty() || normalized.length() > 255
                || !AuthCryptoUtil.EMAIL.matcher(normalized).matches()) {
            throw new BusinessException(400, "邮箱格式不正确");
        }
        return normalized;
    }

    private void requireAuthHash(String authHash) {
        if (authHash == null || !AuthCryptoUtil.AUTH_HASH.matcher(authHash).matches()) {
            throw new BusinessException(400, "主密码格式不正确");
        }
    }

    private String domainOf(String email) {
        int at = email.indexOf('@');
        return at < 0 ? "" : email.substring(at + 1);
    }
}
