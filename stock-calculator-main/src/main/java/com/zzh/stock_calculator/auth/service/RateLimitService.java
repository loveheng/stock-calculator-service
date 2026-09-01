package com.zzh.stock_calculator.auth.service;
import com.zzh.stock_calculator.common.AuthErrorCode;
import com.zzh.stock_calculator.common.BusinessException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 端点限流（docs/e2ee-auth-backend-design.md §D.5.4，决策 B9）。
 *
 * @description Caffeine 计数桶，双维度：IP（X-Forwarded-For 首跳）+ 邮箱。
 *              邮箱维度需读请求体，故由控制器调用本服务（而非拦截器）统一检查；
 *              重启清零为已记录取舍（P2，个人规模可接受）。
 */
@Service
@ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true")
public class RateLimitService {

    private final Cache<String, AtomicLong> registerIp = newBucket(Duration.ofHours(1));
    private final Cache<String, AtomicLong> loginIp = newBucket(Duration.ofMinutes(15));
    private final Cache<String, AtomicLong> loginEmail = newBucket(Duration.ofMinutes(15));
    private final Cache<String, AtomicLong> recoveryRequestIp = newBucket(Duration.ofHours(1));
    private final Cache<String, AtomicLong> recoveryRequestEmail = newBucket(Duration.ofHours(1));
    private final Cache<String, AtomicLong> verifyIp = newBucket(Duration.ofHours(1));
    private final Cache<String, AtomicLong> verifyEmail = newBucket(Duration.ofHours(1));

    private static Cache<String, AtomicLong> newBucket(Duration window) {
        return Caffeine.newBuilder().expireAfterWrite(window).maximumSize(100_000).build();
    }

    public void checkRegister(String ip) {
        hit(registerIp, ip, 5);
    }

    public void checkLogin(String ip, String email) {
        hit(loginIp, ip, 30);
        hit(loginEmail, email, 10);
    }

    public void checkRecoveryRequest(String ip, String email) {
        hit(recoveryRequestIp, ip, 10);
        hit(recoveryRequestEmail, email, 3);
    }

    public void checkVerify(String ip, String email) {
        hit(verifyIp, ip, 20);
        hit(verifyEmail, email, 10);
    }

    private void hit(Cache<String, AtomicLong> bucket, String key, int limit) {
        long count = bucket.asMap().computeIfAbsent(key == null ? "" : key, k -> new AtomicLong()).incrementAndGet();
        if (count > limit) {
            throw new BusinessException(AuthErrorCode.TOO_MANY_REQUESTS, "尝试次数过多，请稍后再试");
        }
    }
}
