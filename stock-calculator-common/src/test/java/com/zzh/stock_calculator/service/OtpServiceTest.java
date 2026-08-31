package com.zzh.stock_calculator.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.config.AuthProperties;
import com.zzh.stock_calculator.entity.OtpCodeEntity;
import com.zzh.stock_calculator.entity.UserEntity;
import com.zzh.stock_calculator.repository.OtpCodeRepository;
import com.zzh.stock_calculator.repository.UserRepository;
import com.zzh.stock_calculator.util.AuthCryptoUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OtpService 单元测试（实行方案 B3 §4.3）：冷却、过期、尝试锁死、单次消费、哈希落库。
 */
@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

    /** 故意大小写混合：issue/verify 内部均需归一化 */
    private static final String EMAIL = "User@Example.COM";
    private static final String NORMALIZED = "user@example.com";

    @Mock private OtpCodeRepository otpCodeRepository;
    @Mock private UserRepository userRepository;
    @Mock private MailService mailService;

    private OtpService otpService;

    @BeforeEach
    void setUp() {
        otpService = new OtpService(otpCodeRepository, userRepository, mailService, new AuthProperties());
    }

    private OtpCodeEntity pendingOtp(String code, OffsetDateTime expiresAt, int attempts) {
        return OtpCodeEntity.builder()
                .email(NORMALIZED)
                .codeHash(AuthCryptoUtil.sha256Hex(NORMALIZED + ":" + code))
                .expiresAt(expiresAt)
                .attempts(attempts)
                .build();
    }

    @Test
    void issueRejectsWhenCooldownActive() {
        OtpCodeEntity latest = OtpCodeEntity.builder().email(NORMALIZED).build();
        latest.setCreatedAt(OffsetDateTime.now().minusSeconds(30)); // < 60s 冷却窗口
        when(otpCodeRepository.findFirstByEmailOrderByCreatedAtDesc(NORMALIZED))
                .thenReturn(Optional.of(latest));

        BusinessException ex = assertThrows(BusinessException.class, () -> otpService.issue(EMAIL));

        assertEquals(429, ex.getCode());
        verify(otpCodeRepository, never()).save(any());
        verify(mailService, never()).sendOtpCode(anyString(), anyString());
    }

    @Test
    void issueSavesHashedOtpAndSendsMail() {
        when(otpCodeRepository.findFirstByEmailOrderByCreatedAtDesc(NORMALIZED))
                .thenReturn(Optional.empty());
        when(otpCodeRepository.save(any(OtpCodeEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        otpService.issue(EMAIL);

        ArgumentCaptor<OtpCodeEntity> captor = ArgumentCaptor.forClass(OtpCodeEntity.class);
        verify(otpCodeRepository).save(captor.capture());
        OtpCodeEntity saved = captor.getValue();
        assertEquals(NORMALIZED, saved.getEmail());
        assertEquals(64, saved.getCodeHash().length(), "验证码必须哈希落库，不得存原文");
        long minutes = Duration.between(OffsetDateTime.now(), saved.getExpiresAt()).toMinutes();
        assertTrue(minutes >= 9 && minutes <= 10, "OTP 有效期应为 10 分钟");
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService).sendOtpCode(eq(NORMALIZED), codeCaptor.capture());
        assertTrue(codeCaptor.getValue().matches("\\d{6}"), "验证码应为 6 位数字");
    }

    @Test
    void issueAllowsAfterCooldownPassed() {
        OtpCodeEntity latest = OtpCodeEntity.builder().email(NORMALIZED).build();
        latest.setCreatedAt(OffsetDateTime.now().minusSeconds(120));
        when(otpCodeRepository.findFirstByEmailOrderByCreatedAtDesc(NORMALIZED))
                .thenReturn(Optional.of(latest));
        when(otpCodeRepository.save(any(OtpCodeEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        otpService.issue(EMAIL);

        verify(mailService).sendOtpCode(eq(NORMALIZED), anyString());
    }

    @Test
    void verifyRejectsMalformedCode() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> otpService.verify(EMAIL, "12a456"));

        assertEquals(400, ex.getCode());
        verify(otpCodeRepository, never())
                .findFirstByEmailAndConsumedAtIsNullOrderByCreatedAtDesc(anyString());
    }

    @Test
    void verifyRejectsWhenNoPendingOtp() {
        when(otpCodeRepository.findFirstByEmailAndConsumedAtIsNullOrderByCreatedAtDesc(NORMALIZED))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> otpService.verify(EMAIL, "123456"));

        assertEquals(400, ex.getCode());
    }

    @Test
    void verifyRejectsExpiredOtp() {
        OtpCodeEntity otp = pendingOtp("123456", OffsetDateTime.now().minusSeconds(1), 0);
        when(otpCodeRepository.findFirstByEmailAndConsumedAtIsNullOrderByCreatedAtDesc(NORMALIZED))
                .thenReturn(Optional.of(otp));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> otpService.verify(EMAIL, "123456"));

        assertEquals(400, ex.getCode());
        assertNull(otp.getConsumedAt(), "过期码不应被标记为已消费");
    }

    @Test
    void verifyWrongCodeIncrementsAttempts() {
        OtpCodeEntity otp = pendingOtp("123456", OffsetDateTime.now().plusMinutes(5), 0);
        when(otpCodeRepository.findFirstByEmailAndConsumedAtIsNullOrderByCreatedAtDesc(NORMALIZED))
                .thenReturn(Optional.of(otp));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> otpService.verify(EMAIL, "654321"));

        assertEquals(400, ex.getCode());
        assertEquals(1, otp.getAttempts());
        assertNull(otp.getConsumedAt());
        verify(otpCodeRepository).save(otp);
    }

    @Test
    void verifyLocksOtpAtMaxAttempts() {
        OtpCodeEntity otp = pendingOtp("123456", OffsetDateTime.now().plusMinutes(5), 4); // 再错一次即达 5
        when(otpCodeRepository.findFirstByEmailAndConsumedAtIsNullOrderByCreatedAtDesc(NORMALIZED))
                .thenReturn(Optional.of(otp));

        assertThrows(BusinessException.class, () -> otpService.verify(EMAIL, "654321"));

        assertNotNull(otp.getConsumedAt(), "达到尝试上限应作废当前码");
        verify(otpCodeRepository).save(otp);
    }

    @Test
    void verifyCorrectCodeConsumesOnceAndReturnsUser() {
        OtpCodeEntity otp = pendingOtp("123456", OffsetDateTime.now().plusMinutes(5), 0);
        when(otpCodeRepository.findFirstByEmailAndConsumedAtIsNullOrderByCreatedAtDesc(NORMALIZED))
                .thenReturn(Optional.of(otp));
        UserEntity user = UserEntity.builder().id(UUID.randomUUID()).email(NORMALIZED).build();
        when(userRepository.findByEmail(NORMALIZED)).thenReturn(Optional.of(user));

        UserEntity result = otpService.verify(EMAIL, " 123456 "); // 含空白时先剔除（防误粘贴）

        assertEquals(user, result);
        assertNotNull(otp.getConsumedAt(), "校验通过即消费（单次）");
        verify(otpCodeRepository).save(otp);
    }
}
