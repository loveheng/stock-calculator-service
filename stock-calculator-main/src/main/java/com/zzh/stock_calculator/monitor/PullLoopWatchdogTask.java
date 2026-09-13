package com.zzh.stock_calculator.monitor;

import com.zzh.stock_calculator.monitor.entity.PullTaskConfigEntity;
import com.zzh.stock_calculator.monitor.repository.PullHeartbeatRepository;
import com.zzh.stock_calculator.monitor.repository.PullTaskConfigRepository;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.message.PullConfigPayload;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * 常态拉取自循环看门狗（docs/pull-loop-unification-design.md §3.4）：main 控制面的
 * 两个周期性职责合一——① 配置快照周期性重推（覆盖式，改配置表后 ≤ 一周期生效）；
 * ② 心跳判活补种（last_renew + ttl + 宽限超期 → 补种子），enabled=false 不补种
 * （L6 停用语义执行者）。补种幂等由 data 侧深度守卫兜底（补多不炸）。
 * <p>种子的「钟」本体在 broker（TTL 到期死信），本类只兜底断绝场景——正常情况下
 * data 消费后续种，看门狗每轮零补种。进程内 lastReseedAt 节流防 data 长时间宕机时
 * 种子在不可消费的工作队列里堆积。</p>
 * <p>§8 拆双节奏：watch() 职责不变（LOOP 判活循环过滤 CALENDAR 行、配置快照仅含
 * LOOP 行，L10）；calendarClaim() 独立 60s 节奏承担日历任务认领——CALENDAR 行永不
 * 进入 LOOP 补种路径（设计不变量 7），broker 侧零在途状态。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PullLoopWatchdogTask {

    /** 判活宽限：完整续期周期之外再放宽 10 分钟（网络抖动 / 单轮超时） */
    private static final long RENEW_GRACE_MS = 10 * 60 * 1000L;

    /** taskCode → delay routing key 显式登记；未登记的配置行跳过并告警 */
    private static final Map<String, String> DELAY_KEYS = Map.of(
            MqKey.TASK_CLS_PULL, MqKey.TASK_CLS_PULL_DELAY,
            MqKey.TASK_ANNOUNCEMENT_COLLECT, MqKey.TASK_ANNOUNCEMENT_COLLECT_DELAY);

    private final PullTaskConfigRepository configRepository;
    private final PullHeartbeatRepository heartbeatRepository;
    private final PullLoopDispatchPort dispatchPort;

    /** 进程内补种节流（main 单副本；重启丢失最多多补一次，深度守卫吸收） */
    private final Map<String, Long> lastReseedAt = new HashMap<>();

    @Scheduled(fixedDelayString = "${pipeline.pull-loop.watch-period-ms:1800000}")
    public void watch() {
        List<PullTaskConfigEntity> configs = configRepository.findAll();
        if (configs.isEmpty()) {
            log.info("pull loop watchdog: 配置表为空（data.sql 未播种？），跳过");
            return;
        }
        pushConfigSnapshot(configs);
        long now = System.currentTimeMillis();
        int reseeded = 0;
        for (PullTaskConfigEntity config : configs) {
            if (!config.isEnabled() || PullTaskConfigEntity.MODE_CALENDAR.equals(config.getScheduleMode())) {
                continue;
            }
            String delayKey = DELAY_KEYS.get(config.getTaskCode());
            if (delayKey == null) {
                log.warn("pull loop watchdog: 未登记的拉取源 taskCode={}，跳过", config.getTaskCode());
                continue;
            }
            if (!isStale(config, now)) {
                continue;
            }
            Long last = lastReseedAt.get(config.getTaskCode());
            if (last != null && now - last < Math.max(config.getTtlMs() * 2, RENEW_GRACE_MS)) {
                continue;
            }
            dispatchPort.dispatchSeed(delayKey, config.getTtlMs());
            lastReseedAt.put(config.getTaskCode(), now);
            reseeded++;
        }
        log.info("pull loop watchdog 完成 configs={} reseeded={}", configs.size(), reseeded);
    }

    /** 配置快照周期性重推（control.pull.config，订阅快照同款覆盖式语义） */
    private void pushConfigSnapshot(List<PullTaskConfigEntity> configs) {
        try {
            dispatchPort.pushConfig(PullConfigPayload.builder()
                    .version(System.currentTimeMillis())
                    .tasks(configs.stream()
                            .filter(config -> !PullTaskConfigEntity.MODE_CALENDAR.equals(config.getScheduleMode()))
                            .map(config -> PullConfigPayload.TaskConfig.builder()
                                    .taskCode(config.getTaskCode())
                                    .enabled(config.isEnabled())
                                    .ttlMs(config.getTtlMs())
                                    .build())
                            .toList())
                    .build());
        } catch (Exception e) {
            log.error("pull config 快照推送失败（下轮重推）: {}", e.toString());
        }
    }

    /** 判活：last_renew + ttl + 宽限 < now → 断绝；无心跳行 = 从未续期，也补 */
    private boolean isStale(PullTaskConfigEntity config, long now) {
        return heartbeatRepository.findById(config.getTaskCode())
                .map(heartbeat -> heartbeat.getLastRenewTime().toInstant().toEpochMilli()
                        + config.getTtlMs() + RENEW_GRACE_MS < now)
                .orElse(true);
    }

    // ==================== 日历任务认领（docs/pull-loop-unification-design.md §8.3.2，L8-L14） ====================

    /** 启动校验（L13 fail-fast）：CALENDAR 行 cron 表达式必须可解析，坏行阻止 main 启动 */
    @PostConstruct
    void validateCalendarRows() {
        for (PullTaskConfigEntity row : configRepository.findByScheduleMode(PullTaskConfigEntity.MODE_CALENDAR)) {
            if (row.getCronExpression() == null || row.getCronExpression().isBlank()) {
                throw new IllegalStateException("calendar task " + row.getTaskCode() + " cron_expression 为空");
            }
            CronExpression.parse(row.getCronExpression());
        }
    }

    /**
     * 日历任务认领（§8.3.2）：CAS 推进游标先于投递（affected=1 者独得资格，多副本安全，L12）；
     * 投递失败回滚游标保持逾期（下轮重认领，槽位不丢）；游标 NULL = 待初始化——补齐为下一
     * 日历点且不触发执行（crontab 语义）；补跑恒 skip-missed（下一槽位从 now 算，L9）。
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
                    dispatchPort.dispatchCalendarTask(row.getTaskCode());
                    claimed++;
                    log.info("calendar claim: {} 已认领投递（消耗槽位 {}，下一槽位 {}）", row.getTaskCode(), cursor, next);
                } catch (Exception e) {
                    configRepository.rollbackCalendarCursor(row.getTaskCode(), cursor, utcNow());
                    log.error("calendar claim: {} 投递失败，游标回滚至 {}（下轮重认领）: {}", row.getTaskCode(), cursor, e.toString());
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
