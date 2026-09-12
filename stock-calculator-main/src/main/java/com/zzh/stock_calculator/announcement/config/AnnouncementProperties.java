package com.zzh.stock_calculator.announcement.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * announcement.* 配置（MQ 单路径终态）：
 * 主服务只保留 状态机/发布/订阅/快照 相关键；采集与解析配置随管道移交数据服务
 * （data 侧 CollectorProperties / AnnouncementParseProperties）。
 */
@Data
@ConfigurationProperties(prefix = "announcement")
public class AnnouncementProperties {

    private final Process process = new Process();
    private final Snapshot snapshot = new Snapshot();
    private final Pdf pdf = new Pdf();

    /** 订阅护栏：stockId 格式与每用户数量上限（防对 CNINFO 打无效解析） */
    private final Subscription subscription = new Subscription();

    @Data
    public static class Subscription {
        /** 每用户订阅标的数上限（防滥用：订阅即触发 CNINFO 全量首拉） */
        private int maxPerUser = 100;
    }

    @Data
    public static class Process {
        private boolean enabled = false;
        private String cron = "0 1 * * * *";
        /** 瞬时失败计次上限，达限落 FAILED 终态（result.announcement.failed 消费端落账） */
        private int maxFailAttempts = 3;
        /** RATE_LIMITED 上报后暂停任务发布的冷却窗口（分钟，§4.5 发布端熔断） */
        private long rateLimitCooldownMinutes = 30;
    }

    /**
     * 订阅快照下发（设计文档 §8 阶段 4/R3）：
     * 定时重推周期兜底快照丢失（R6 恢复窗口）；注：@Scheduled 占位符默认值须与此处一致。
     */
    @Data
    public static class Snapshot {
        private String cron = "0 */30 * * * *";
    }

    @Data
    public static class Pdf {
        /** 超过即拒绝（§5 内存炸弹防线；result.announcement.collected 超体积终态判定） */
        private int maxSizeMb = 50;
    }
}
