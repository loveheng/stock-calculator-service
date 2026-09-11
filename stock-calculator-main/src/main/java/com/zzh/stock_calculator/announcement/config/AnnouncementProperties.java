package com.zzh.stock_calculator.announcement.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;
import java.util.List;

/**
 * announcement.* 配置（设计文档 §7）。
 * 注：process.* 为 §7 之外骨架期新增键（批处理开关/周期/失败上限），已在实现中标注。
 */
@Data
@ConfigurationProperties(prefix = "announcement")
public class AnnouncementProperties {

    private final Sync sync = new Sync();
    private final Process process = new Process();
    private final Snapshot snapshot = new Snapshot();
    private final Pdf pdf = new Pdf();
    private final Clean clean = new Clean();
    private final Distill distill = new Distill();
    private final Throttle throttle = new Throttle();

    /** 订阅护栏：stockId 格式与每用户数量上限（防对 CNINFO 打无效解析） */
    private final Subscription subscription = new Subscription();

    @Data
    public static class Sync {
        private boolean enabled = false;
        private String cron = "0 0 * * * *";
        /** 公告分类过滤（category_ndbg_szsh = 年报；空 = 全部，S3 实证） */
        private String category = "";
        /** 无订阅行时的种子标的，元素支持 "code" 或 "code,orgId"；纯订阅驱动语义下默认空 */
        private List<String> seedStockList = List.of();
        private FirstPullMode firstPullMode = FirstPullMode.FULL;
        /** 全量首拉下界（近 3 年护栏） */
        private LocalDate historySince = LocalDate.of(2023, 1, 1);
        /** LOOKBACK 模式回看天数 */
        private long lookbackDays = 90;
        /** 2023 之前历史仅标题命中 long-term-keywords 才入库（§7 长效白名单） */
        private boolean longTermEnabled = false;
        private List<String> longTermKeywords = List.of("招股说明书", "公司章程", "重大资产重组", "控制权变更");
    }

    @Data
    public static class Subscription {
        /** 每用户订阅标的数上限（防滥用：订阅即触发 CNINFO 全量首拉） */
        private int maxPerUser = 100;
    }

    @Data
    public static class Process {
        private boolean enabled = false;
        private String cron = "0 1 * * * *";
        /** 瞬时失败计次上限，达限落 FAILED 终态 */
        private int maxFailAttempts = 3;
        /** RATE_LIMITED 上报后暂停任务发布的冷却窗口（分钟，§4.5 发布端熔断，任务 3） */
        private long rateLimitCooldownMinutes = 30;
    }

    /**
     * 订阅快照下发（设计文档 §8 阶段 4/R3，datasvc.mq.enabled=true 时生效）：
     * 定时重推周期兜底快照丢失（R6 恢复窗口）；注：@Scheduled 占位符默认值须与此处一致。
     */
    @Data
    public static class Snapshot {
        private String cron = "0 */30 * * * *";
    }

    @Data
    public static class Pdf {
        /** 超过即拒绝载入内存（§5 内存炸弹防线） */
        private int maxSizeMb = 50;
        private int parseConcurrency = 1;
        private int parseTimeoutSeconds = 120;
    }

    @Data
    public static class Clean {
        /** 跨页重复行剔除阈值（§4.2 v0.2 接入） */
        private double headerRepeatRatio = 0.3;
        /** 单页最小有效字符数（扫描页判定，v0.2 接入） */
        private int noTextMinCharsPerPage = 50;
    }

    @Data
    public static class Distill {
        /** 蒸馏模型名（S5 接入） */
        private String model = "";
        /** Prompt 版本留档 */
        private String promptVersion = "";
    }

    @Data
    public static class Throttle {
        /** CNINFO 全部请求最小间隔 */
        private long batchIntervalMs = 300;
    }

    /** 首拉模式：FULL=historySince 起全量 / LOOKBACK=近 N 天 */
    public enum FirstPullMode {
        FULL, LOOKBACK
    }
}
