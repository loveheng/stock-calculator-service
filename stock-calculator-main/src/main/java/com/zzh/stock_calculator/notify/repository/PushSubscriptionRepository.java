package com.zzh.stock_calculator.notify.repository;

import com.zzh.stock_calculator.notify.entity.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, Long> {

    /** 按用户取全部订阅（推送投递目标集合） */
    List<PushSubscription> findByUserId(String userId);

    boolean existsByEndpoint(String endpoint);

    /**
     * 单语句幂等 upsert：endpoint 唯一冲突即整行覆盖（同一浏览器重复订阅更新密钥）。
     * last_success_at 保留原值（NULL 或历史成功时间），不因重登记被抹掉。
     *
     * @return 受影响行数（恒 1）
     */
    @Modifying
    @Query(value = """
            INSERT INTO push_subscription (user_id, endpoint, p256dh, auth, user_agent)
            VALUES (:userId, :endpoint, :p256dh, :auth, :userAgent)
            ON CONFLICT (endpoint) DO UPDATE SET
                user_id    = EXCLUDED.user_id,
                p256dh     = EXCLUDED.p256dh,
                auth       = EXCLUDED.auth,
                user_agent = EXCLUDED.user_agent,
                updated_at = NOW()
            """, nativeQuery = true)
    int upsert(@Param("userId") String userId,
               @Param("endpoint") String endpoint,
               @Param("p256dh") String p256dh,
               @Param("auth") String auth,
               @Param("userAgent") String userAgent);

    /** 注销：按 endpoint 物理删除（幂等：0 行 = 本就不存在） */
    @Modifying
    @Query(value = "DELETE FROM push_subscription WHERE endpoint = :endpoint", nativeQuery = true)
    int deleteByEndpoint(@Param("endpoint") String endpoint);

    /** 按用户注销全部订阅（换绑/登出清理） */
    @Modifying
    @Query(value = "DELETE FROM push_subscription WHERE user_id = :userId", nativeQuery = true)
    int deleteByUserId(@Param("userId") String userId);

    /** 投递成功后刷新活性时间戳（僵尸订阅判定依据） */
    @Modifying
    @Query(value = "UPDATE push_subscription SET last_success_at = NOW(), updated_at = NOW() WHERE endpoint = :endpoint",
            nativeQuery = true)
    int touchSuccess(@Param("endpoint") String endpoint);

    /**
     * 惰性清理僵尸订阅：last_success_at 早于截止时间（或 NULL 且登记早于截止时间）的物理删除。
     * 截止时间由调用方按「90 天无成功触达」计算；在投递路径顺带触发，无独立定时任务。
     *
     * @return 删除行数
     */
    @Modifying
    @Query(value = """
            DELETE FROM push_subscription
            WHERE COALESCE(last_success_at, created_at) < :staleBefore
            """, nativeQuery = true)
    int pruneStale(@Param("staleBefore") java.time.OffsetDateTime staleBefore);
}
