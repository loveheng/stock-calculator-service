package com.zzh.stock_calculator.announcement.repository;

import com.zzh.stock_calculator.announcement.entity.AnnouncementSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 订阅表访问（设计文档 §4.1/D13）。
 */
public interface AnnouncementSubscriptionRepository extends JpaRepository<AnnouncementSubscription, Long> {

    /** 订阅幂等判定 */
    Optional<AnnouncementSubscription> findByUserIdAndStockId(UUID userId, String stockId);

    List<AnnouncementSubscription> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** 采集期 orgId 锚点（最早订阅行） */
    Optional<AnnouncementSubscription> findFirstByStockIdOrderByCreatedAtAsc(String stockId);

    /** orgId 回填全部订阅行 */
    List<AnnouncementSubscription> findByStockId(String stockId);

    /** 定时同步标的清单（去重） */
    @Query("select distinct s.stockId from AnnouncementSubscription s")
    List<String> findDistinctStockIds();

    /** 每用户订阅数（上限护栏判定） */
    int countByUserId(UUID userId);
}
