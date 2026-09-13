package com.zzh.stock_calculator.monitor.repository;

import com.zzh.stock_calculator.monitor.entity.PullTaskConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 常态拉取任务配置仓库（docs/pull-loop-unification-design.md §3/§8）。
 * 日历认领三语句（§8.3.2，L12）各自独立短事务——认领/回滚不跨 MQ 投递持锁；
 * 批量 UPDATE 绕过 @UpdateTimestamp 生命周期，故显式写 updatedAt。
 */
@Repository
public interface PullTaskConfigRepository extends JpaRepository<PullTaskConfigEntity, String> {

    List<PullTaskConfigEntity> findByScheduleMode(String scheduleMode);

    /** 游标初始化（NULL → 下一日历点，不触发执行，crontab 语义）：仅首个到达者生效 */
    @Modifying
    @Transactional
    @Query("UPDATE PullTaskConfigEntity c SET c.nextExpectedTime = :next, c.updatedAt = :now "
            + "WHERE c.taskCode = :taskCode AND c.nextExpectedTime IS NULL")
    int initCalendarCursor(@Param("taskCode") String taskCode,
                           @Param("next") OffsetDateTime next,
                           @Param("now") OffsetDateTime now);

    /** 认领：CAS 推进游标，affected=1 者独得投递资格（多副本安全）；逾期条件与推进同行原子 */
    @Modifying
    @Transactional
    @Query("UPDATE PullTaskConfigEntity c SET c.nextExpectedTime = :next, c.updatedAt = :now "
            + "WHERE c.taskCode = :taskCode AND c.enabled = true "
            + "AND c.scheduleMode = 'CALENDAR' AND c.nextExpectedTime <= :now")
    int claimCalendarSlot(@Param("taskCode") String taskCode,
                          @Param("next") OffsetDateTime next,
                          @Param("now") OffsetDateTime now);

    /** 投递失败回滚：游标退回原逾期值（保持逾期，下轮重认领，槽位不丢） */
    @Modifying
    @Transactional
    @Query("UPDATE PullTaskConfigEntity c SET c.nextExpectedTime = :overdue, c.updatedAt = :now "
            + "WHERE c.taskCode = :taskCode")
    int rollbackCalendarCursor(@Param("taskCode") String taskCode,
                               @Param("overdue") OffsetDateTime overdue,
                               @Param("now") OffsetDateTime now);
}
