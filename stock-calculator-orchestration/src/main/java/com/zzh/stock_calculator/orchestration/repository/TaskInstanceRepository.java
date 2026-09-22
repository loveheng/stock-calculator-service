package com.zzh.stock_calculator.orchestration.repository;

import com.zzh.stock_calculator.orchestration.entity.TaskInstanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaskInstanceRepository extends JpaRepository<TaskInstanceEntity, Long> {

    Optional<TaskInstanceEntity> findByTraceId(String traceId);

    /** 进程重启恢复（§八 可靠性）：捞未完成实例续跑 */
    List<TaskInstanceEntity> findByStatus(String status);

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
