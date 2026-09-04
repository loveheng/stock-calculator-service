package com.zzh.stock_calculator.auth.service;
import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.auth.config.AuthProperties;
import com.zzh.stock_calculator.auth.entity.OtpCodeEntity;
import com.zzh.stock_calculator.auth.entity.UserEntity;
import com.zzh.stock_calculator.auth.repository.OtpCodeRepository;
import com.zzh.stock_calculator.auth.repository.UserRepository;
import com.zzh.stock_calculator.auth.util.AuthCryptoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final PlatformTransactionManager txnMgr;

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
     * @description 刻意不加 @Transactional：本方法写操作经 REQUIRES_NEW 独立事务提交——
     *              attempts 计数若随外层事务回滚被抹掉，「5 次锁死」永不生效（冒烟实测发现）；
     *              消费同理（签发会话失败时码已作废、需重新申请，单次消费语义）。
     *              用编程式事务而非依赖 save() 自带事务：save() 是 REQUIRED，会被调用方
     *              事务 JOIN——独立提交的保证必须显式，不能只活在注释里。
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
            saveOtpInNewTxn(otp);
            throw new BusinessException(400, "验证码错误或已过期");
        }
        otp.setConsumedAt(OffsetDateTime.now());
        saveOtpInNewTxn(otp);
        UserEntity user = userRepository.findByEmail(normalized)
                .orElseThrow(() -> new BusinessException(400, "验证码错误或已过期"));
        log.info("otp verified, email={}", maskEmail(normalized));
        return user;
    }

    /** REQUIRES_NEW 事务模板（同 AiChatOrchestrationService 模式）：写独立于任何外层事务 */
    private TransactionTemplate requiresNewTxn() {
        TransactionTemplate tx = new TransactionTemplate(txnMgr);
        tx.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
        return tx;
    }

    /** 独立事务持久化 OTP 状态（attempts 计数/作废/消费）：不受调用方事务回滚影响 */
    private void saveOtpInNewTxn(OtpCodeEntity otp) {
        requiresNewTxn().execute(status -> {
            otpCodeRepository.save(otp);
            return null;
        });
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
