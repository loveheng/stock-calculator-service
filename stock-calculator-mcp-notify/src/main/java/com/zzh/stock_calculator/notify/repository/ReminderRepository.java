package com.zzh.stock_calculator.notify.repository;

import com.zzh.stock_calculator.notify.entity.ReminderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * reminder 表访问（docs/notify/design.md §4.1/§五）：
 * - fire 消费幂等判重：triggerOccupy 以 CAS 抢占（status+due 双条件），redeliver 时失败方跳过；
 * - bootstrap 全量重投影：启动扫 active 的 at_time 提醒。
 */
public interface ReminderRepository extends JpaRepository<ReminderEntity, Long> {

    List<ReminderEntity> findByUserIdAndStatusOrderByCreatedAtDesc(String userId, String status);

    List<ReminderEntity> findByStatusAndTriggerType(String status, String triggerType);

    /** fire 消费幂等占位（§五判重）：仅 active 且 fired_at 未到 due 时置 fired_at，
     *  返回 1 = 本次触发有效；0 = 重复种子/已 done/cancelled（种子不回收，幂等挡） */
    @Modifying
    @Query("""
            UPDATE ReminderEntity r
               SET r.firedAt = :due, r.fireCount = r.fireCount + 1, r.updatedAt = CURRENT_TIMESTAMP
             WHERE r.id = :id
               AND r.status = 'active'
               AND (r.firedAt IS NULL OR r.firedAt < :due)
            """)
    int triggerOccupy(@Param("id") Long id, @Param("due") OffsetDateTime due);

    /** 限幅命中记录（N7：窗口内重复命中只计数不触发） */
    @Modifying
    @Query("""
            UPDATE ReminderEntity r
               SET r.suppressedCount = r.suppressedCount + 1, r.updatedAt = CURRENT_TIMESTAMP
             WHERE r.id = :id
            """)
    int markSuppressed(@Param("id") Long id);

    /** 状态流转定向更新（active→done/cancelled）：整行 save 会用陈旧实体覆盖
     *  triggerOccupy 刚写入的 fired_at/fire_count（丢失更新），禁用整行写 */
    @Modifying
    @Query("""
            UPDATE ReminderEntity r
               SET r.status = :status, r.updatedAt = CURRENT_TIMESTAMP
             WHERE r.id = :id
            """)
    int markStatus(@Param("id") Long id, @Param("status") String status);
}
