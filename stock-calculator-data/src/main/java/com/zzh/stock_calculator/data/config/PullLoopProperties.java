package com.zzh.stock_calculator.data.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 自循环常态拉取配置（datasvc.pullloop 前缀，docs/pull-loop-unification-design.md §3）。
 * 代码内置默认值为冷启动兜底（循环等不到配置 = 死循环，设计不变量 5）；
 * control.pull.config 快照只做覆盖。注册在 CollectorConfig（collector 门控），
 * worker 变体无此配置也无拉取消费者。
 */
@Data
@ConfigurationProperties(prefix = "datasvc.pullloop")
public class PullLoopProperties {

    private final Cls cls = new Cls();

    private final Announcement announcement = new Announcement();

    @Data
    public static class Cls {

        /** CLS 电报常态拉取续期开关 */
        private boolean enabled = true;

        /** 种子 TTL 默认值（毫秒，原 ClsPullTask fixedDelay 8 分钟节奏） */
        private long ttlMs = 480_000L;
    }

    @Data
    public static class Announcement {

        /** 公告常态采集续期开关 */
        private boolean enabled = true;

        /** 种子 TTL 默认值（毫秒，原 cron 每小时节奏；整点错峰随任务化取消，评估可接受） */
        private long ttlMs = 3_600_000L;
    }
}
