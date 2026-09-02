package com.zzh.stock_calculator.vision.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 视觉结果缓存的 Redis 实现（决策 B12）。
 *
 * @description 纯 String 值存取（OCR 文本原样；结构化结果由调用方转 JSON），
 *              TTL 由调用方按各自配置传入。所有操作 try/catch 降级：
 *              get 失败视作未命中（回源渠道调用），put/evict 失败仅打日志，
 *              保证 OCR/LLM 主链路不受 Redis 可用性影响。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisVisionCacheStore implements VisionCacheStore {

    private final StringRedisTemplate redisTemplate;

    @Override
    public String get(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("vision cache get degraded, fallback to channel call: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void put(String key, String value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (Exception e) {
            log.debug("vision cache put skipped: {}", e.getMessage());
        }
    }

    @Override
    public void evict(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.debug("vision cache evict skipped: {}", e.getMessage());
        }
    }
}
