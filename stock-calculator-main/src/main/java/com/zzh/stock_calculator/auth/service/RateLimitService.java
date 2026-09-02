package com.zzh.stock_calculator.auth.service;

import com.zzh.stock_calculator.common.AuthErrorCode;
import com.zzh.stock_calculator.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 端点限流（docs/e2ee-auth-backend-design.md §D.5.4，决策 B9 → B11 升级为 Redis 计数）。
 *
 * @description 双维度：IP（X-Forwarded-For 首跳）+ 邮箱；计数桶 INCR + 首次 EXPIRE 定窗，
 *              存 Redis：应用重启不清零（原 Caffeine 方案的 P2 取舍就此消除），未来多实例天然共享。
 *              故障策略 fail-open：Redis 不可用时放行并告警（可用性优先，个人规模合理取舍）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true")
public class RateLimitService {

    private static final Duration WINDOW_REGISTER_IP = Duration.ofHours(1);
    private static final Duration WINDOW_LOGIN = Duration.ofMinutes(15);
    private static final Duration WINDOW_RECOVERY_REQUEST_IP = Duration.ofHours(1);
    private static final Duration WINDOW_RECOVERY_REQUEST_EMAIL = Duration.ofHours(1);
    private static final Duration WINDOW_VERIFY = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;

    public void checkRegister(String ip) {
        hit("rl:reg:ip", ip, 5, WINDOW_REGISTER_IP);
    }

    public void checkLogin(String ip, String email) {
        hit("rl:login:ip", ip, 30, WINDOW_LOGIN);
        hit("rl:login:email", email, 10, WINDOW_LOGIN);
    }

    public void checkRecoveryRequest(String ip, String email) {
        hit("rl:rec:ip", ip, 10, WINDOW_RECOVERY_REQUEST_IP);
        hit("rl:rec:email", email, 3, WINDOW_RECOVERY_REQUEST_EMAIL);
    }

    public void checkVerify(String ip, String email) {
        hit("rl:verify:ip", ip, 20, WINDOW_VERIFY);
        hit("rl:verify:email", email, 10, WINDOW_VERIFY);
    }

    private void hit(String bucket, String key, int limit, Duration window) {
        String redisKey = bucket + ":" + (key == null ? "" : key);
        try {
            Long count = redisTemplate.opsForValue().increment(redisKey);
            if (count == null) {
                return; // 事务/管道模式下可能为 null，视为未计数
            }
            if (count == 1) {
                // 仅首次写入时定窗（固定窗口语义）；INCR 与 EXPIRE 非原子为已知微小竞态，
                // 最坏后果是单个桶缺 TTL 永久限流，个人规模可接受（重启/手动 DEL 可清）
                redisTemplate.expire(redisKey, window);
            }
            if (count > limit) {
                throw new BusinessException(AuthErrorCode.TOO_MANY_REQUESTS, "尝试次数过多，请稍后再试");
            }
        } catch (BusinessException e) {
            throw e; // 429 必须穿透，不得被下面的降级分支吞掉
        } catch (DataAccessException e) {
            log.warn("rate limit degraded (fail-open), redis unavailable: {}", e.getMessage());
        }
    }
}
