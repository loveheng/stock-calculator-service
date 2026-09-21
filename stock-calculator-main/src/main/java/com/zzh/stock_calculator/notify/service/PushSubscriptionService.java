package com.zzh.stock_calculator.notify.service;

import com.zzh.stock_calculator.notify.entity.PushSubscription;
import com.zzh.stock_calculator.notify.repository.PushSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 推送订阅管理：登记 / 注销 / 按用户枚举。
 *
 * @description 纯 CRUD 门面，Controller 与投递服务的唯一入口；
 *              upsert 以 endpoint 为唯一键（同一浏览器重复订阅覆盖密钥）。
 * @author 开发团队
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushSubscriptionService {

    private final PushSubscriptionRepository repository;

    /** 登记/刷新订阅（幂等 upsert） */
    @Transactional
    public void subscribe(String userId, String endpoint, String p256dh, String auth, String userAgent) {
        repository.upsert(userId, endpoint, p256dh, auth, userAgent);
        log.info("[push] 订阅登记 userId={} endpoint={}...{}", userId,
                endpoint.substring(0, Math.min(48, endpoint.length())),
                endpoint.substring(Math.max(0, endpoint.length() - 8)));
    }

    /** 注销单个订阅（用户主动关闭或浏览器注销） */
    @Transactional
    public void unsubscribe(String endpoint) {
        int n = repository.deleteByEndpoint(endpoint);
        log.info("[push] 订阅读销 endpoint 尾8位=...{} 删除 {} 行",
                endpoint.substring(Math.max(0, endpoint.length() - 8)), n);
    }

    /** 注销该用户全部订阅 */
    @Transactional
    public void unsubscribeAll(String userId) {
        int n = repository.deleteByUserId(userId);
        log.info("[push] 用户订阅全注销 userId={} 删除 {} 行", userId, n);
    }

    /** 投递目标集合 */
    public List<PushSubscription> listByUser(String userId) {
        return repository.findByUserId(userId);
    }
}
