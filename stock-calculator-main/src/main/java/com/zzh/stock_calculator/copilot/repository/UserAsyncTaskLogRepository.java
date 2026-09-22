package com.zzh.stock_calculator.copilot.repository;

import com.zzh.stock_calculator.copilot.entity.UserAsyncTaskLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 异步任务映射审计表访问（步 6-1 通道映射块）：按 correlationId 还原 user 推 SSE，
 * 按 status 扫超期 RUNNING（GC 兜底），按 user_id+时间窗查历史（限流频控数据源）。
 */
public interface UserAsyncTaskLogRepository extends JpaRepository<UserAsyncTaskLog, Long> {

    Optional<UserAsyncTaskLog> findByCorrelationId(String correlationId);

    Optional<UserAsyncTaskLog> findByTaskId(String taskId);

    /** GC 兜底：扫超期 RUNNING（@Scheduled 定时清理） */
    List<UserAsyncTaskLog> findByStatus(String status);

    /** 限流频控：user 时间窗内创建的任务数 */
    long countByUserIdAndCreatedAtAfter(String userId, OffsetDateTime after);

    /** 限流并发：user 在途（RUNNING）任务数 */
    long countByUserIdAndStatus(String userId, String status);

    /** 终态回写（事件消费侧）：CAS 语义防事件乱序覆盖终态 */
    @Modifying
    @Query("UPDATE UserAsyncTaskLog t SET t.status = :status, t.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE t.taskId = :taskId AND t.status = 'RUNNING'")
    int finalizeIfRunning(@Param("taskId") String taskId, @Param("status") String status);
}
