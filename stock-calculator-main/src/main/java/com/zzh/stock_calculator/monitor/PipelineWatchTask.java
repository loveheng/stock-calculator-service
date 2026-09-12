package com.zzh.stock_calculator.monitor;

import com.zzh.stockcalc.contract.MqQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据管线巡检（data-service-split-design.md R4 落地，MQ 单路径终态的可靠性兜底面）。
 * 数据管线无进程内回退路径：停摆的发现与提醒由本任务承担，恢复靠数据源窗口自愈（D3）
 * + 对账器（D6）+ 人工重启 data 服务。
 *
 * <p>检查项（先行 + 滞后双指标）：
 * ① 任务队列 consumer 数 = 0 → 数据服务停机/消费端消失（DATA_DOWN）；
 * ② 任务/结果队列深度超阈值 → 积压（BACKLOG）；
 * ③ dead.q 有存量 → 毒消息/重试耗尽（DEAD_Q）；
 * ④ 管理 API 不可达 → broker 本身异常（BROKER_UNREACHABLE，fail-loud）；
 * ⑤ DB 滞后指标：announcement / cls_article_embedding PENDING 最老年龄超阈值（PENDING_AGE）。
 * 每项独立冷却（内存态，重启清零最多多发一封），触发即发布 {@link PipelineAlertEvent}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineWatchTask {

    static final String KIND_DATA_DOWN = "DATA_DOWN";
    static final String KIND_BACKLOG = "BACKLOG";
    static final String KIND_DEAD_Q = "DEAD_Q";
    static final String KIND_PENDING_AGE = "PENDING_AGE";
    static final String KIND_BROKER_UNREACHABLE = "BROKER_UNREACHABLE";

    /** 任务队列（data 侧消费）；consumer 归零 = 数据服务停机（all-in-one 部署口径） */
    private static final List<String> TASK_QUEUES =
            List.of(MqQueue.TASK_ANNOUNCEMENT_PROCESS, MqQueue.TASK_EMBEDDING_COMPUTE);

    private final RabbitManagementClient managementClient;
    private final JdbcTemplate jdbcTemplate;
    private final PipelineWatchProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    /** 同类告警冷却表：key = kind[:queue] */
    private final Map<String, Instant> lastAlertAt = new ConcurrentHashMap<>();

    @Scheduled(cron = "${pipeline.watch.cron:0 */5 * * * *}")
    public void watch() {
        if (!properties.isEnabled()) {
            return;
        }
        List<RabbitManagementClient.QueueStat> stats;
        try {
            stats = managementClient.fetchQueueStats();
        } catch (Exception e) {
            log.warn("pipeline watch: 管理 API 不可达: {}", e.getMessage());
            alert(KIND_BROKER_UNREACHABLE, "【管线告警】MQ 管理 API 不可达",
                    "巡检无法查询队列状态（" + properties.getMgmtBaseUrl() + "）。\n"
                    + "可能原因：broker 停机 / 网络分区 / 管理 API 未监听。\n"
                    + "数据管线（CLS/公告采集与处理）可能整体停摆，请尽快检查。\n错误：" + e.getMessage());
            return;
        }
        Map<String, RabbitManagementClient.QueueStat> byName = new java.util.HashMap<>();
        for (RabbitManagementClient.QueueStat stat : stats) {
            byName.put(stat.name(), stat);
        }

        // ① 任务队列 consumer 归零 = 数据服务停机（替代已删除的回退开关，停机必报）
        for (String queue : TASK_QUEUES) {
            RabbitManagementClient.QueueStat stat = byName.get(queue);
            if (stat != null && stat.consumers() == 0) {
                alert(KIND_DATA_DOWN + ":" + queue, "【管线告警】数据服务消费端消失",
                        "队列 " + queue + " 当前 consumer 数为 0，数据服务（worker）可能已停机。\n"
                        + "管线停摆期间任务在队列中排队持久化，恢复后自动续跑；超过源站窗口期的数据需人工触发补录。\n"
                        + "处置：检查 stock-calculator-data 进程/容器并重启。");
            }
        }

        // ② 队列积压（含 result.ingest.q：主服务消费端卡死先行指标）
        for (Map.Entry<String, RabbitManagementClient.QueueStat> entry : byName.entrySet()) {
            String name = entry.getKey();
            if (MqQueue.DEAD.equals(name)) {
                continue;
            }
            RabbitManagementClient.QueueStat stat = entry.getValue();
            if (stat.messages() >= properties.getQueueBacklogThreshold()) {
                alert(KIND_BACKLOG + ":" + name, "【管线告警】队列积压 " + name,
                        "队列 " + name + " 积压 " + stat.messages() + " 条（阈值 "
                        + properties.getQueueBacklogThreshold() + "），consumer 数=" + stat.consumers() + "。\n"
                        + "若长时间不消化，检查 data 服务 worker 与主服务消费端日志。");
            }
        }

        // ③ 死信停放
        RabbitManagementClient.QueueStat dead = byName.get(MqQueue.DEAD);
        if (dead != null && dead.messages() > 0) {
            alert(KIND_DEAD_Q, "【管线告警】死信队列有存量",
                    "dead.q 现有 " + dead.messages() + " 条消息（重试 3 次仍失败后停放）。\n"
                    + "请结合日志定位毒消息来源，必要时经管理台重放或清理。");
        }

        // ④ DB 滞后指标：PENDING 最老年龄（broker 指标全部失灵时的兜底信号）
        pendingAge("announcement", "SELECT COUNT(*), COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at))), 0)"
                + " FROM announcement WHERE status = 'PENDING'");
        pendingAge("cls_article_embedding",
                "SELECT COUNT(*), COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at))), 0)"
                        + " FROM cls_article_embedding WHERE status = 'PENDING'");
    }

    private void pendingAge(String kind, String sql) {
        try {
            jdbcTemplate.query(sql, rs -> {
                if (!rs.next()) {
                    return;
                }
                long count = rs.getLong(1);
                long ageSeconds = rs.getLong(2);
                long ageHours = ageSeconds / 3600;
                if (count > 0 && ageHours >= properties.getMaxPendingAgeHours()) {
                    alert(KIND_PENDING_AGE + ":" + kind,
                            "【管线告警】" + kind + " PENDING 停滞",
                            kind + " 有 " + count + " 条 PENDING，最老已停滞 " + ageHours + " 小时"
                                    + "（阈值 " + properties.getMaxPendingAgeHours() + " 小时）。\n"
                                    + "MQ 指标可能失灵或对账链路失效，请检查对应处理管道与对账任务日志。");
                }
            });
        } catch (Exception e) {
            // DB 检查失败只记日志：不因巡检自身故障制造告警风暴
            log.warn("pipeline watch: PENDING 巡检失败 kind={}: {}", kind, e.getMessage());
        }
    }

    /** 冷却去重后发布告警事件（auth 侧邮件监听器发送；PIPELINE_ALERT_EMAIL 未配置时静默） */
    private void alert(String key, String subject, String body) {
        Instant now = Instant.now();
        Instant last = lastAlertAt.get(key);
        if (last != null && now.minusSeconds(properties.getAlertCooldownMinutes() * 60L).isBefore(last)) {
            log.debug("pipeline watch: alert suppressed (cooldown), key={}", key);
            return;
        }
        lastAlertAt.put(key, now);
        eventPublisher.publishEvent(PipelineAlertEvent.builder()
                .kind(key)
                .subject(subject)
                .body(body)
                .occurredAtEpochMs(now.toEpochMilli())
                .build());
        log.warn("pipeline watch: alert published key={}", key);
    }
}
