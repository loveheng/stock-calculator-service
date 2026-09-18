package com.zzh.stock_calculator.monitor;

import com.zzh.stock_calculator.monitor.entity.PullTaskConfigEntity;
import com.zzh.stock_calculator.monitor.repository.PullTaskConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 日历型定时任务统一认领调度器（docs/architecture/pull-loop-unification.md §8.3.2/§10）：
 * 任务管理统一（2026-09-18 合表）后唯一的 CALENDAR 认领循环——pull_task_config 全部
 * schedule_mode=CALENDAR 行（MQ 投递型 task.* + 进程内执行型 job.*）都在此认领，
 * 分发按注册表路由：taskCode 命中 {@link AppTaskHandler} 注册 → 进程内 handler.run()
 * （原 AppTaskScheduler 职责并入）；未命中 → MQ 直发（PullLoopDispatchPort，§8，
 * task.hello.world）。认领语义两源本就同构（§8.3.2）：
 * <ul>
 *   <li>CAS 推进游标先于执行/投递（affected=1 者独得资格，多副本安全，L12）；</li>
 *   <li>执行/投递失败回滚游标保持逾期（下轮重认领，槽位不丢）；</li>
 *   <li>游标 NULL = 待初始化：补齐为下一日历点且不触发执行（crontab 语义）；</li>
 *   <li>补跑恒 skip-missed（下一槽位从 now 算，L9）。</li>
 * </ul>
 * <p>基础设施级开关 app-task.enabled（默认 true，非业务配置）只压制进程内半区——
 * E2E 测试经此关掉 main 本地 job.* 定时器，MQ 型 CALENDAR 行不受影响。</p>
 */
@Slf4j
@Component
public class CalendarTaskClaimScheduler {

    private final PullTaskConfigRepository configRepository;
    private final PullLoopDispatchPort dispatchPort;
    private final List<AppTaskHandler> handlerList;

    /** 基础设施开关（app-task.enabled，默认 true）：false = 进程内 job.* 行整体压制 */
    private final boolean appTaskEnabled;

    /** taskCode → handler 注册表（validate() 构建）：命中 = 进程内执行，未命中 = MQ 投递 */
    private Map<String, AppTaskHandler> handlers = Map.of();

    /** 显式构造器：appTaskEnabled 需经 @Value 解析（@RequiredArgsConstructor 不传递注解到参数） */
    public CalendarTaskClaimScheduler(PullTaskConfigRepository configRepository,
                                      PullLoopDispatchPort dispatchPort,
                                      List<AppTaskHandler> handlerList,
                                      @Value("${app-task.enabled:true}") boolean appTaskEnabled) {
        this.configRepository = configRepository;
        this.dispatchPort = dispatchPort;
        this.handlerList = handlerList;
        this.appTaskEnabled = appTaskEnabled;
    }

    /**
     * 启动校验（fail-fast）：handler 注册表 taskCode 不得重复；CALENDAR 行 cron 必须可解析；
     * job.* 行无对应 handler / handler 无配置行各打 warn 不阻断（前者多为 data.sql 播种了
     * 未接入的任务，后者为漏播种；task.* 行本就无 handler，不校验）。
     */
    @PostConstruct
    void validate() {
        Map<String, AppTaskHandler> registry = new HashMap<>();
        for (AppTaskHandler handler : handlerList) {
            if (registry.put(handler.taskCode(), handler) != null) {
                throw new IllegalStateException("app task handler taskCode 重复: " + handler.taskCode());
            }
        }
        handlers = Map.copyOf(registry);
        List<PullTaskConfigEntity> rows = configRepository.findByScheduleMode(PullTaskConfigEntity.MODE_CALENDAR);
        for (PullTaskConfigEntity row : rows) {
            if (row.getCronExpression() == null || row.getCronExpression().isBlank()) {
                throw new IllegalStateException("calendar task " + row.getTaskCode() + " cron_expression 为空");
            }
            CronExpression.parse(row.getCronExpression());
            if (row.getTaskCode().startsWith("job.") && !handlers.containsKey(row.getTaskCode())) {
                log.warn("calendar task: job 行 {} 无对应 handler，不会执行", row.getTaskCode());
            }
        }
        for (String taskCode : handlers.keySet()) {
            boolean hasRow = rows.stream().anyMatch(row -> row.getTaskCode().equals(taskCode));
            if (!hasRow) {
                log.warn("calendar task: handler {} 无配置行，永不调度（data.sql 漏播种？）", taskCode);
            }
        }
    }

    /**
     * 认领循环（§8.3.2 同款三语句）：逐行认领逾期游标，按注册表分发——进程内执行或 MQ 投递。
     * job.* 行未注册 handler 时静默跳过（启动校验已 warn；绝不改道 MQ，防 unroutable 投递）。
     */
    @Scheduled(fixedDelayString = "${pipeline.pull-loop.calendar-claim-period-ms:60000}")
    public void calendarClaim() {
        List<PullTaskConfigEntity> rows = configRepository.findByScheduleMode(PullTaskConfigEntity.MODE_CALENDAR);
        long now = System.currentTimeMillis();
        int claimed = 0;
        for (PullTaskConfigEntity row : rows) {
            if (!row.isEnabled()) {
                continue;
            }
            AppTaskHandler handler = handlers.get(row.getTaskCode());
            if (handler != null && !appTaskEnabled) {
                continue; // app-task.enabled=false：进程内任务整体压制（对齐原 AppTaskScheduler bean 缺位语义）
            }
            if (handler == null && row.getTaskCode().startsWith("job.")) {
                continue; // 播种了未接入的 job 行：静默跳过（不落 MQ）
            }
            OffsetDateTime next = nextFire(row, now);
            if (next == null) {
                continue;
            }
            OffsetDateTime cursor = row.getNextExpectedTime();
            if (cursor == null) {
                configRepository.initCalendarCursor(row.getTaskCode(), next, utcNow());
                log.info("calendar claim: {} 游标初始化 → {}（不触发执行，crontab 语义）", row.getTaskCode(), next);
                continue;
            }
            if (cursor.toInstant().toEpochMilli() > now) {
                continue;
            }
            if (configRepository.claimCalendarSlot(row.getTaskCode(), next, utcNow()) == 1) {
                try {
                    if (handler != null) {
                        handler.run();
                    } else {
                        dispatchPort.dispatchCalendarTask(row.getTaskCode());
                    }
                    claimed++;
                    log.info("calendar claim: {} 已认领执行（{}，消耗槽位 {}，下一槽位 {}）",
                            row.getTaskCode(), handler != null ? "进程内" : "MQ", cursor, next);
                } catch (Exception e) {
                    configRepository.rollbackCalendarCursor(row.getTaskCode(), cursor, utcNow());
                    log.error("calendar claim: {} 执行失败，游标回滚至 {}（下轮重认领）: {}", row.getTaskCode(), cursor, e.toString());
                }
            }
        }
        if (claimed > 0) {
            log.info("calendar claim 完成 rows={} claimed={}", rows.size(), claimed);
        }
    }

    /** 下一触发点（行时区求值，L13）；解析失败 / 无下一触发点返回 null（均已落日志） */
    private OffsetDateTime nextFire(PullTaskConfigEntity row, long nowMillis) {
        try {
            ZonedDateTime now = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.of(row.getTimezone()));
            ZonedDateTime next = CronExpression.parse(row.getCronExpression()).next(now);
            if (next == null) {
                log.error("calendar claim: {} cron 无下一触发点（表达式={}），跳过", row.getTaskCode(), row.getCronExpression());
                return null;
            }
            return next.toInstant().atOffset(ZoneOffset.UTC);
        } catch (Exception e) {
            log.error("calendar claim: {} cron 解析失败（表达式={}）: {}", row.getTaskCode(), row.getCronExpression(), e.toString());
            return null;
        }
    }

    /** 游标统一以 UTC OffsetDateTime 落 timestamptz（绝对时刻与行时区求值结果等价） */
    private OffsetDateTime utcNow() {
        return Instant.ofEpochMilli(System.currentTimeMillis()).atOffset(ZoneOffset.UTC);
    }
}
