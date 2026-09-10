package com.zzh.stock_calculator.announcement.service;

import com.zzh.stock_calculator.announcement.config.AnnouncementProperties;
import com.zzh.stock_calculator.announcement.entity.AnnouncementSubscription;
import com.zzh.stock_calculator.announcement.event.SubscriptionCreatedEvent;
import com.zzh.stock_calculator.announcement.repository.AnnouncementSubscriptionRepository;
import com.zzh.stock_calculator.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 订阅服务（设计文档 D13）：订阅写库 + 事务内发事件（AFTER_COMMIT 语义依赖事务）。
 * 幂等：已订阅 → false 不重复触发抓取；退订幂等删除，公告数据保留。
 * 抓取编排完全在监听器，与本服务分离（事件与抓取逻辑解耦）。
 * 护栏：stockId 六位数字校验 + 每用户订阅数上限（防对 CNINFO 打无效解析/滥用）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementSubscriptionService {

    /** A 股股票代码：6 位数字（防垃圾代码对 CNINFO topSearch 打无效请求） */
    private static final Pattern STOCK_ID_PATTERN = Pattern.compile("\\d{6}");

    private final AnnouncementSubscriptionRepository subscriptionRepository;
    private final AnnouncementProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    /** @return true=新建订阅（已发事件），false=已存在（幂等跳过） */
    @Transactional
    public boolean subscribe(UUID userId, String stockId) {
        if (stockId == null || !STOCK_ID_PATTERN.matcher(stockId).matches()) {
            throw new BusinessException(400, "stockId 非法：须为 6 位数字股票代码");
        }
        if (subscriptionRepository.findByUserIdAndStockId(userId, stockId).isPresent()) {
            return false;
        }
        int max = properties.getSubscription().getMaxPerUser();
        if (subscriptionRepository.countByUserId(userId) >= max) {
            throw new BusinessException(400, "订阅数量已达上限（" + max + "）");
        }
        AnnouncementSubscription saved = subscriptionRepository.save(AnnouncementSubscription.builder()
                .userId(userId)
                .stockId(stockId)
                .build());
        eventPublisher.publishEvent(SubscriptionCreatedEvent.builder()
                .stockId(saved.getStockId())
                .orgId(saved.getOrgId())
                .build());
        return true;
    }

    /** @return true=删除成功，false=本就未订阅（幂等） */
    @Transactional
    public boolean unsubscribe(UUID userId, String stockId) {
        return subscriptionRepository.findByUserIdAndStockId(userId, stockId)
                .map(row -> {
                    subscriptionRepository.delete(row);
                    return true;
                })
                .orElse(false);
    }

    /** 我的订阅清单（创建时间倒序） */
    public List<AnnouncementSubscription> list(UUID userId) {
        return subscriptionRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }
}
