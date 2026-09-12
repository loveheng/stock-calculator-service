package com.zzh.stock_calculator.monitor;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * pipeline.watch.* 配置（data-service-split-design.md R4：队列堆积/数据服务停机告警）。
 * 数据管线无回退开关（MQ 单路径终态），可靠性由「源头窗口自愈 + 对账器 + 本巡检告警」承担。
 */
@Data
@ConfigurationProperties(prefix = "pipeline.watch")
public class PipelineWatchProperties {

    /** 巡检总开关 */
    private boolean enabled = true;

    /** 巡检周期（默认 5 分钟一次） */
    private String cron = "0 */5 * * * *";

    /** LavinMQ/RabbitMQ 管理 API 基址（队列深度/consumer 数经此查询） */
    private String mgmtBaseUrl = "http://localhost:15672";

    /** 管理 API 账号（与 AMQP 账号同源） */
    private String mgmtUsername = "guest";
    private String mgmtPassword = "guest";

    /** vhost（默认 /，管理 API 路径中需转义为 %2F） */
    private String vhost = "/";

    /** 任务/结果队列积压告警阈值（条） */
    private int queueBacklogThreshold = 100;

    /** DB 侧 PENDING 最老年龄告警阈值（小时） */
    private int maxPendingAgeHours = 24;

    /** 同类告警冷却窗口（分钟，内存态：重启后冷却清零，最多多发一封） */
    private int alertCooldownMinutes = 30;
}
