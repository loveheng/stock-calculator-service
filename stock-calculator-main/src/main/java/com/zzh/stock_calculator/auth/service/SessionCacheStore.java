package com.zzh.stock_calculator.auth.service;

import com.zzh.stock_calculator.auth.entity.AuthSessionEntity;

/**
 * 会话热读缓存抽象（决策 B11）：数据库仍是唯一事实源，本接口只承载热点读。
 *
 * @description 分离原则：读走缓存（cache-aside）、写穿 DB 后驱逐；任何实现故障都必须降级为
 *              「视作未命中」，绝不阻塞认证主链路。key 一律用 tokenHash（SHA-256），原文不落缓存。
 */
public interface SessionCacheStore {

    /** 未命中 / 故障降级时返回 null */
    AuthSessionEntity get(String tokenHash);

    /**
     * 写入缓存；ttl 由调用方计算（min(缓存上限, 会话剩余有效期)）。
     * 故障降级：静默吞掉异常（记 debug 日志），下次 resolve 自然回源 DB。
     */
    void put(String tokenHash, AuthSessionEntity session, java.time.Duration ttl);

    /** 写路径（吊销 / 续期）后调用，保证吊销立即生效；故障降级同 put */
    void evict(String tokenHash);
}
