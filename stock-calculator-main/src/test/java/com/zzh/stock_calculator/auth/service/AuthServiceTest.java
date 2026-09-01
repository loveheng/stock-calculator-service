package com.zzh.stock_calculator.auth.service;
import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.auth.config.AuthProperties;
import com.zzh.stock_calculator.auth.dto.AuthSessionResponse;
import com.zzh.stock_calculator.auth.dto.LoginRequest;
import com.zzh.stock_calculator.auth.dto.RegisterRequest;
import com.zzh.stock_calculator.auth.entity.UserEntity;
import com.zzh.stock_calculator.auth.repository.UserProfileRepository;
import com.zzh.stock_calculator.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthService 单元测试（实行方案 B3 §4.3）：纯 Mockito，不启动 Spring 上下文。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    /** 故意带首尾空格与大小写混合：覆盖归一化等价（trim + 小写） */
    private static final String EMAIL = "  User@Example.COM  ";
    private static final String NORMALIZED = "user@example.com";
    private static final String AUTH_HASH = "a".repeat(64);

    @Mock private UserRepository userRepository;
    @Mock private UserProfileRepository userProfileRepository;
    @Mock private SessionService sessionService;
    @Mock private OtpService otpService;

    private AuthService authService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, userProfileRepository,
                sessionService, otpService, new AuthProperties());
    }

    private AuthSessionResponse session(UUID userId) {
        return AuthSessionResponse.builder().userId(userId).token("token-xyz").build();
    }

    @Test
    void registerSuccessNormalizesEmailAndHashesPassword() {
        when(userRepository.existsByEmail(NORMALIZED)).thenReturn(false);
        when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sessionService.issueFull(any(UUID.class), anyInt()))
                .thenAnswer(inv -> session(inv.getArgument(0)));

        AuthSessionResponse resp = authService.register(register(EMAIL, AUTH_HASH));

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).save(captor.capture());
        UserEntity saved = captor.getValue();
        assertEquals(NORMALIZED, saved.getEmail());
        assertTrue(encoder.matches(AUTH_HASH, saved.getPasswordHash()));
        assertNotEquals(AUTH_HASH, saved.getPasswordHash());
        assertEquals(saved.getId(), resp.getUserId());
    }

    @Test
    void registerDuplicateEmailConflicts409() {
        when(userRepository.existsByEmail(NORMALIZED)).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.register(register(EMAIL, AUTH_HASH)));

        assertEquals(409, ex.getCode());
        verify(userRepository, never()).save(any());
    }

    @Test
    void registerRejectsNonHex64Password() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.register(register(EMAIL, "short-pass")));

        assertEquals(400, ex.getCode());
        verify(userRepository, never()).save(any());
    }

    @Test
    void registerRejectsInvalidEmail() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.register(register("not-an-email", AUTH_HASH)));

        assertEquals(400, ex.getCode());
    }

    @Test
    void loginSuccessTrimsLowercasesEmailAndFillsHasProfile() {
        UUID userId = UUID.randomUUID();
        UserEntity user = UserEntity.builder()
                .id(userId).email(NORMALIZED).passwordHash(encoder.encode(AUTH_HASH)).build();
        when(userRepository.findByEmail(NORMALIZED)).thenReturn(Optional.of(user));
        when(sessionService.issueFull(eq(userId), anyInt())).thenReturn(session(userId));
        when(userProfileRepository.existsById(userId)).thenReturn(true);

        AuthSessionResponse resp = authService.login(login(EMAIL, AUTH_HASH, null));

        assertTrue(resp.getHasProfile());
        assertEquals(userId, resp.getUserId());
    }

    @Test
    void loginWrongPasswordUnified400() {
        UUID userId = UUID.randomUUID();
        UserEntity user = UserEntity.builder()
                .id(userId).email(NORMALIZED).passwordHash(encoder.encode("b".repeat(64))).build();
        when(userRepository.findByEmail(NORMALIZED)).thenReturn(Optional.of(user));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.login(login(NORMALIZED, AUTH_HASH, null)));

        assertEquals(400, ex.getCode());
        assertEquals("邮箱或主密码错误", ex.getMessage());
    }

    @Test
    void loginUnknownEmailRunsDummyBcryptThenUnified400() {
        when(userRepository.findByEmail(NORMALIZED)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.login(login(NORMALIZED, AUTH_HASH, null)));

        assertEquals(400, ex.getCode());
        assertEquals("邮箱或主密码错误", ex.getMessage());
    }

    @Test
    void loginTtlDaysFromRequestOverridesDefault() {
        UUID userId = UUID.randomUUID();
        UserEntity user = UserEntity.builder()
                .id(userId).email(NORMALIZED).passwordHash(encoder.encode(AUTH_HASH)).build();
        when(userRepository.findByEmail(NORMALIZED)).thenReturn(Optional.of(user));
        when(sessionService.issueFull(eq(userId), eq(30))).thenReturn(session(userId));
        when(userProfileRepository.existsById(userId)).thenReturn(false);

        AuthSessionResponse resp = authService.login(login(NORMALIZED, AUTH_HASH, 30));

        assertFalse(resp.getHasProfile());
    }

    private RegisterRequest register(String email, String password) {
        RegisterRequest req = new RegisterRequest();
        req.setEmail(email);
        req.setPassword(password);
        return req;
    }

    private LoginRequest login(String email, String password, Integer ttlDays) {
        LoginRequest req = new LoginRequest();
        req.setEmail(email);
        req.setPassword(password);
        req.setTtlDays(ttlDays);
        return req;
    }
}
