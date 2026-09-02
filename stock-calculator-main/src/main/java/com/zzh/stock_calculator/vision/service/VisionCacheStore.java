package com.zzh.stock_calculator.vision.service;

import java.time.Duration;

/**
 * 视觉结果缓存抽象（决策 B12：内存缓存全部移除，识别结果统一存 Redis）。
 *
 * @description 值一律 String（对象与 JSON 的互转由调用方负责），key 由调用方拼接业务前缀
 *              （vision:ocr:text: / vision:ai:draft: / vision:executor:）。
 *              降级策略与 auth.SessionCacheStore 一致：Redis 不可用时 get 视作未命中、
 *              put/evict 静默跳过——识别主链路绝不因缓存故障失败，仅损失额度节省。
 */
public interface VisionCacheStore {

    /** 命中返回存储值（可能为 ""，业务空结果）；未命中或 Redis 故障返回 null */
    String get(String key);

    /** 写入缓存并设定存活时长；Redis 故障时静默跳过 */
    void put(String key, String value, Duration ttl);

    /** 驱逐缓存（强制刷新场景）；Redis 故障时静默跳过 */
    void evict(String key);
}
