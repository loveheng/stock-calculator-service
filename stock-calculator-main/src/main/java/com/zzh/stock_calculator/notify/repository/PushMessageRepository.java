package com.zzh.stock_calculator.notify.repository;

import com.zzh.stock_calculator.notify.entity.PushMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PushMessageRepository extends JpaRepository<PushMessage, Long> {

    /** 最近消息列表（倒序，UI 分页首屏） */
    List<PushMessage> findTop50ByUserIdOrderByCreatedAtDesc(String userId);

    /** 未读数（角标） */
    long countByUserIdAndReadAtIsNull(String userId);

    /** 全部标记已读（幂等：已读行 read_at 不再改写） */
    @Modifying
    @Query(value = "UPDATE push_message SET read_at = NOW() WHERE user_id = :userId AND read_at IS NULL",
            nativeQuery = true)
    int markAllRead(@Param("userId") String userId);

    /**
     * 惰性清理：仅保留该用户最近 100 条，物理删除更早的历史（防表无限膨胀）。
     * 在拉取列表 / 全部已读时顺带触发（无独立定时任务，对齐 notify design N2「钟不在进程」）。
     *
     * @return 删除行数
     */
    @Modifying
    @Query(value = """
            DELETE FROM push_message WHERE user_id = :userId AND id NOT IN (
                SELECT id FROM push_message WHERE user_id = :userId
                ORDER BY created_at DESC LIMIT 100)
            """, nativeQuery = true)
    int pruneKeepRecent100(@Param("userId") String userId);
}
