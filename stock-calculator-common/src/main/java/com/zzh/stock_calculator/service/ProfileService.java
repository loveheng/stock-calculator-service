package com.zzh.stock_calculator.service;

import com.zzh.stock_calculator.common.AuthErrorCode;
import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.common.ProfileConflictException;
import com.zzh.stock_calculator.dto.auth.ProfileResponse;
import com.zzh.stock_calculator.dto.auth.ProfileUpsertRequest;
import com.zzh.stock_calculator.entity.UserProfileEntity;
import com.zzh.stock_calculator.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * 密文档案存取（docs/e2ee-auth-backend-design.md §D.4.2 端点 4/5）。
 *
 * @description 仅属主可读写（userId 来自会话拦截器，非请求参数）；
 *              updatedAt 兼作 If-Match 版本号（决策 B5），防御跨设备孤儿竞态（§D.6.4）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true")
public class ProfileService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final UserProfileRepository userProfileRepository;

    /**
     * 版本字符串统一归一为 UTC 规范形：Hibernate flush 生成的值带 JVM 时区（+08:00），
     * JDBC 读回的值为 UTC（Z），同一时刻两种文本直接字符串比较会误判 409（冒烟实测发现）。
     */
    private static String canonicalVersion(OffsetDateTime updatedAt) {
        return ISO.format(updatedAt.withOffsetSameInstant(ZoneOffset.UTC));
    }

    /** If-Match 按时刻比较而非文本比较：容忍任意等价时区表示，格式非法视为不匹配 */
    private static boolean sameInstant(String ifMatch, OffsetDateTime updatedAt) {
        try {
            return OffsetDateTime.parse(ifMatch.trim()).toInstant().equals(updatedAt.toInstant());
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** 读取；缺行抛 404（前端合法中间态，驱动孤儿引导 / 补传，等价 maybeSingle 语义） */
    public ProfileResponse get(UUID userId) {
        UserProfileEntity profile = userProfileRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.NOT_FOUND, "用户档案尚未创建"));
        return toResponse(profile);
    }

    /**
     * Upsert（If-Match 语义见《设计》§D.4.4）：
     * 无行 → 无条件创建（两设备同时首建时，后到者自动落入"已有行"分支被拦）；
     * 有行 → ifMatch 必须等于当前 updatedAt（ISO-8601 字符串比较），否则 409 并携带服务端版本。
     */
    @Transactional
    public ProfileResponse upsert(UUID userId, ProfileUpsertRequest request, String ifMatch) {
        validateCiphertext(request);
        Optional<UserProfileEntity> existing = userProfileRepository.findById(userId);
        if (existing.isEmpty()) {
            UserProfileEntity created = UserProfileEntity.builder()
                    .id(userId)
                    .passwordPayload(request.getPasswordPayload())
                    .passwordIv(request.getPasswordIv())
                    .recoveryPayload(request.getRecoveryPayload())
                    .recoveryIv(request.getRecoveryIv())
                    .build();
            // saveAndFlush：@UpdateTimestamp 在 flush 时才生成，响应必须携带落库后的真实版本（If-Match 契约）
            UserProfileEntity saved = userProfileRepository.saveAndFlush(created);
            log.info("profile created, userId={}", userId);
            return toResponse(saved);
        }
        UserProfileEntity profile = existing.get();
        String currentVersion = canonicalVersion(profile.getUpdatedAt());
        if (ifMatch == null || ifMatch.isBlank() || !sameInstant(ifMatch, profile.getUpdatedAt())) {
            throw new ProfileConflictException(currentVersion);
        }
        profile.setPasswordPayload(request.getPasswordPayload());
        profile.setPasswordIv(request.getPasswordIv());
        profile.setRecoveryPayload(request.getRecoveryPayload());
        profile.setRecoveryIv(request.getRecoveryIv());
        UserProfileEntity saved = userProfileRepository.saveAndFlush(profile);
        return toResponse(saved);
    }

    /** 四密文非空 + Base64 可解码（防脏数据落库；服务端不校验解密语义） */
    private void validateCiphertext(ProfileUpsertRequest request) {
        if (request == null) {
            throw new BusinessException(400, "请求体不能为空");
        }
        requireBase64("passwordPayload", request.getPasswordPayload());
        requireBase64("passwordIv", request.getPasswordIv());
        requireBase64("recoveryPayload", request.getRecoveryPayload());
        requireBase64("recoveryIv", request.getRecoveryIv());
    }

    private void requireBase64(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(400, field + " 不能为空");
        }
        try {
            Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, field + " 不是合法的 Base64 密文");
        }
    }

    private ProfileResponse toResponse(UserProfileEntity profile) {
        return ProfileResponse.builder()
                .passwordPayload(profile.getPasswordPayload())
                .passwordIv(profile.getPasswordIv())
                .recoveryPayload(profile.getRecoveryPayload())
                .recoveryIv(profile.getRecoveryIv())
                .updatedAt(profile.getUpdatedAt() == null ? null : canonicalVersion(profile.getUpdatedAt()))
                .build();
    }
}
