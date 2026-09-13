package com.zzh.stock_calculator.data.announcement;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 公告解析配置（data 侧平移，默认值与主服务 announcement.clean 一致）。
 * data 不持有 DB/采集语义，仅解析相关的清洗阈值。
 */
@Data
@ConfigurationProperties(prefix = "announcement.parse")
public class AnnouncementParseProperties {

    private final Clean clean = new Clean();

    /** CNINFO PDF 下载节流最小间隔（毫秒），与 collector 侧同口径默认 */
    private long throttleBatchIntervalMs = 300;

    /** PDF 体积上限 MB（CninfoPdfClient 下载护栏，内存炸弹防御） */
    private int pdfMaxSizeMb = 50;

    @Data
    public static class Clean {

        /** 跨页重复行剔除阈值（§4.2 v0.2 接入） */
        private double headerRepeatRatio = 0.3;

        /** 单页最小有效字符数（扫描页判定，v0.2 接入） */
        private int noTextMinCharsPerPage = 50;
    }
}
