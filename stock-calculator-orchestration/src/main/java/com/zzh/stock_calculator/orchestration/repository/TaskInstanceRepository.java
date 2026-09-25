package com.zzh.stock_calculator.orchestration.repository;

import com.zzh.stock_calculator.orchestration.entity.TaskInstanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskInstanceRepository extends JpaRepository<TaskInstanceEntity, Long> {

    Optional<TaskInstanceEntity> findByTraceId(String traceId);

    /** 3② 增量摘要：同 plan 上次成功执行实例（$.env.last_execution_time 数据源） */
    Optional<TaskInstanceEntity> findTopByPlanIdAndStatusOrderByUpdatedAtDesc(Long planId, String status);

    /** 进程重启恢复（§八 可靠性）：捞未完成实例续跑 */
    List<TaskInstanceEntity> findByStatus(String status);

    /** 超时扫描专用：waiting 且 wait_deadline 已过（SQL 级收口，替代全量捞后内存 filter） */
    List<TaskInstanceEntity> findByStatusAndWaitDeadlineBefore(String status, java.time.LocalDateTime deadline);

    /**
     * fan-in 预过滤（P3+ 性能债修复）：仅捞 plan_dag_snapshot 中存在「未执行 mq_wait 节点且
     * event 与 routing 前缀匹配」的 waiting 实例——替代 O(waiting 全量) 内存扫描，filter 归一
     * 匹配仍留 Java 侧（MqWaitWakeService.matchDomainWaitNode）。jsonb_exists 函数形式规避
     * ? 操作符与 JDBC 占位符冲突。
     */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT * FROM task_instance
            WHERE status = 'waiting'
              AND EXISTS (SELECT 1 FROM jsonb_array_elements(plan_dag_snapshot -> 'nodes') n
                          WHERE n ->> 'type' = 'mq_wait'
                            AND n ->> 'event' <> ''
                            AND NOT jsonb_exists(node_states, n ->> 'id')
                            AND (:routing = 'event.' || (n ->> 'event')
                                 OR :routing LIKE 'event.' || (n ->> 'event') || '.%'))
            """, nativeQuery = true)
    List<TaskInstanceEntity> findWaitingWithDomainEvent(@org.springframework.data.repository.query.Param("routing") String routing);

    /** 保留策略（OrchestrationRetentionTask）：终态实例到期滚动删除（waiting/running 不动） */
    long deleteByStatusInAndUpdatedAtBefore(java.util.Collection<String> statuses, java.time.LocalDateTime cutoff);


    /** HITL plan 详情：最近真实执行记录（人工审核判断依据） */
    List<TaskInstanceEntity> findByPlanIdOrderByUpdatedAtDesc(Long planId, org.springframework.data.domain.PageRequest page);

    /**
     * 步 6-1 多实例并发定案：同 plan 严格串行（真同步）——事务级 advisory lock，
     * 会话结束（事务提交/回滚）自动释放；并发实例在同 plan 上排队，防重复调外部接口。
     * 调用点必须在事务内（Executor.run @Transactional）。
     */
    @org.springframework.data.jpa.repository.Query(
            value = "SELECT pg_advisory_xact_lock(hashtext(:planId))", nativeQuery = true)
    void acquirePlanLock(@org.springframework.data.repository.query.Param("planId") String planId);
}
