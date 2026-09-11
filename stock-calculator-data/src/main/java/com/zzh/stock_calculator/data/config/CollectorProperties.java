package com.zzh.stock_calculator.data.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;
import java.util.List;

/**
 * collector 角色公告采集配置（datasvc.collector.announcement 前缀，设计文档 §5/§8 阶段 4）。
 * enabled 缺省 false：@ConditionalOnProperty 无 matchIfMissing 是项目既有约定
 * （防未显式配置的进程意外打真实 CNINFO）；yml 显式开启。
 * <p>首拉/长效白名单/节流键语义与主服务 AnnouncementProperties.Sync 同源
 * （采集逻辑迁出 D1，主服务保留同名键仅作回退路径）。</p>
 */
@Data
@ConfigurationProperties(prefix = "datasvc.collector")
public class CollectorProperties {

    private final Announcement announcement = new Announcement();

    @Data
    public static class Announcement {

        /** 公告采集开关（叠加在 datasvc.collector.enabled 之上） */
        private boolean enabled = false;

        /** 采集周期（小时级，与主服务原 sync.cron 节奏对齐、错开整点分钟） */
        private String cron = "0 5 * * * *";

        /** 公告分类过滤（category_ndbg_szsh = 年报；空 = 全部，S3 实证） */
        private String category = "";

        /** 首拉模式：FULL=historySince 起全量 / LOOKBACK=近 N 天 */
        private FirstPullMode firstPullMode = FirstPullMode.FULL;

        /** 全量首拉下界（近 3 年护栏） */
        private LocalDate historySince = LocalDate.of(2023, 1, 1);

        /** LOOKBACK 模式回看天数 */
        private long lookbackDays = 90;

        /** 首拉前历史仅标题命中 long-term-keywords 才发布（§7 长效白名单） */
        private boolean longTermEnabled = false;

        private List<String> longTermKeywords = List.of("招股说明书", "公司章程", "重大资产重组", "控制权变更");

        /** CNINFO 全部请求最小间隔（毫秒） */
        private long throttleBatchIntervalMs = 300;

        private int maxPages = 200;

        private int pageSize = 30;

        /** PDF 体积上限 MB（worker 下载护栏，任务 4 复用；采集侧不拦，状态机归主服务 D7） */
        private int pdfMaxSizeMb = 50;
    }

    public enum FirstPullMode { FULL, LOOKBACK }
}
