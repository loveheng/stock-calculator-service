package com.zzh.stock_calculator.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.config.AuthProperties;
import com.zzh.stock_calculator.entity.OtpCodeEntity;
import com.zzh.stock_calculator.entity.UserEntity;
import com.zzh.stock_calculator.repository.OtpCodeRepository;
import com.zzh.stock_calculator.repository.UserRepository;
import com.zzh.stock_calculator.util.AuthCryptoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 找回验证码（docs/e2ee-auth-backend-design.md §D.5.2）：找回唯一通道的最后一道防线。
 *
 * @description 6 位 / 10 分钟 / 单次消费 / 5 次尝试锁死 / 60s 同邮箱冷却 / 哈希落库。
 *              校验失败统一 400 "验证码错误或已过期"，不泄露具体失败原因。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true")
public class OtpService {

    private final OtpCodeRepository otpCodeRepository;
    private final UserRepository userRepository;
    private final MailService mailService;
    private final AuthProperties authProperties;

    /** 签发并发送验证码；同邮箱冷却期内抛 429 */
    @Transactional
    public void issue(String email) {
        String normalized = AuthCryptoUtil.normalizeEmail(email);
        otpCodeRepository.findFirstByEmailOrderByCreatedAtDesc(normalized).ifPresent(latest -> {
            if (latest.getCreatedAt() != null && latest.getCreatedAt().isAfter(
                    OffsetDateTime.now().minusSeconds(authProperties.getOtpCooldownSeconds()))) {
                throw new BusinessException(429, "验证码发送过于频繁，请稍后再试");
            }
        });
        String code = AuthCryptoUtil.randomOtp();
        OtpCodeEntity entity = OtpCodeEntity.builder()
                .email(normalized)
                .codeHash(AuthCryptoUtil.sha256Hex(normalized + ":" + code))
                .expiresAt(OffsetDateTime.now().plusMinutes(authProperties.getOtpTtlMinutes()))
                .build();
        otpCodeRepository.save(entity);
        mailService.sendOtpCode(normalized, code); // 发送失败 → 事务回滚，不残留死码
    }

    /**
     * 校验验证码；成功即消费（单次）。
     *
     * @param code 6 位数字；含空白时先剔除（防误粘贴）
     * @return 校验通过的用户实体（供签发 recovery 会话）
     *
     * @description 刻意不加 @Transactional：失败路径的 attempts 计数必须落在独立短事务里提交，
     *              否则随外层事务回滚被抹掉，「5 次锁死」永不生效（冒烟实测发现）。
     */
    public UserEntity verify(String email, String code) {
        String normalized = AuthCryptoUtil.normalizeEmail(email);
        if (code == null || !AuthCryptoUtil.OTP_CODE.matcher(code.trim()).matches()) {
            throw new BusinessException(400, "验证码错误或已过期");
        }
        OtpCodeEntity otp = otpCodeRepository.findFirstByEmailAndConsumedAtIsNullOrderByCreatedAtDesc(normalized)
                .orElseThrow(() -> new BusinessException(400, "验证码错误或已过期"));
        if (otp.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new BusinessException(400, "验证码错误或已过期");
        }
        String computed = AuthCryptoUtil.sha256Hex(normalized + ":" + code.trim());
        if (!AuthCryptoUtil.constantTimeEquals(computed, otp.getCodeHash())) {
            otp.setAttempts(otp.getAttempts() + 1);
            if (otp.getAttempts() >= authProperties.getOtpMaxAttempts()) {
                otp.setConsumedAt(OffsetDateTime.now()); // 达到上限：作废当前码，需重新申请
                log.warn("otp locked by attempts, email={}", maskEmail(normalized));
            }
            otpCodeRepository.save(otp);
            throw new BusinessException(400, "验证码错误或已过期");
        }
        otp.setConsumedAt(OffsetDateTime.now());
        otpCodeRepository.save(otp);
        UserEntity user = userRepository.findByEmail(normalized)
                .orElseThrow(() -> new BusinessException(400, "验证码错误或已过期"));
        log.info("otp verified, email={}", maskEmail(normalized));
        return user;
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
