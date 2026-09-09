package com.zzh.stock_calculator.auth.service;

import com.zzh.stock_calculator.crawler.EmbeddingStatsReportEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 向量化统计报告邮件（crawler 统计事件的 auth 侧适配器，默认每 3 天一份）。
 *
 * <p>依赖方向 auth → crawler 基包 API（Modulith 合规，无环）。存量回填完成后仅输出
 * 增量段（用户约定）；收件人经 EMBEDDING_NOTIFY_EMAIL 注入，未配置即跳过；
 * MailService 仅在 app.auth.enabled=true 装配，缺失时 ObjectProvider 兜底跳过；
 * 发送失败仅 WARN，不影响统计任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingStatsMailListener {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final ZoneId ZONE_BEIJING = ZoneId.of("Asia/Shanghai");

    private final ObjectProvider<MailService> mailServiceProvider;

    /** 收件人（.env 注入 EMBEDDING_NOTIFY_EMAIL；为空时不发送，与完成通知共用） */
    @Value("${EMBEDDING_NOTIFY_EMAIL:}")
    private String notifyEmail;

    @EventListener
    public void onStatsReport(EmbeddingStatsReportEvent event) {
        if (notifyEmail == null || notifyEmail.isBlank()) {
            log.info("embedding stats report ready, EMBEDDING_NOTIFY_EMAIL not set, skip mail");
            return;
        }
        MailService mailService = mailServiceProvider.getIfAvailable();
        if (mailService == null) {
            log.warn("embedding stats report ready but mail service unavailable (app.auth.enabled=false), skip mail");
            return;
        }
        String window = formatWindow(event.getWindowStartEpochSecond(), event.getWindowEndEpochSecond());
        String body = event.isBackfillComplete()
                ? renderIncremental(event, window)
                : renderProgress(event, window);
        try {
            mailService.sendText(notifyEmail, "【股票计算助手】向量化统计报告 " + window, body);
        } catch (Exception e) {
            log.warn("embedding stats report mail failed, email={}", notifyEmail, e);
        }
    }

    private String formatWindow(long startSecond, long endSecond) {
        LocalDateTime start = LocalDateTime.ofInstant(Instant.ofEpochSecond(startSecond), ZONE_BEIJING);
        LocalDateTime end = LocalDateTime.ofInstant(Instant.ofEpochSecond(endSecond), ZONE_BEIJING);
        return start.format(TIME_FMT) + " ~ " + end.format(TIME_FMT);
    }

    /** 存量进行中：完整模板（存量进度 + 新增数据） */
    private String renderProgress(EmbeddingStatsReportEvent event, String window) {
        long pending = Math.max(event.getTotalArticles() - event.getDoneCount() - event.getFailedCount(), 0);
        long newEmbedded = Math.max(event.getNewArticleCount() - event.getNewPendingCount(), 0);
        double pct = event.getTotalArticles() > 0
                ? event.getDoneCount() * 100.0 / event.getTotalArticles() : 0.0;
        return "cls_article 向量化统计报告（" + window + "，北京时间）\n\n"
                + "一、存量回填进度\n"
                + "文章总数：" + event.getTotalArticles() + "\n"
                + "已向量化：" + event.getDoneCount() + "（" + String.format("%.1f", pct) + "%）\n"
                + "永久失败：" + event.getFailedCount() + "\n"
                + "待处理：" + pending + "\n"
                + "本周期完成嵌入：" + event.getEmbeddedInWindow() + " 条\n\n"
                + "二、新增数据\n"
                + "本周期新增电报：" + event.getNewArticleCount() + " 条\n"
                + "已嵌入：" + newEmbedded + " 条 / 待处理：" + event.getNewPendingCount() + " 条\n\n"
                + "（本邮件由向量化统计任务定期自动发送）";
    }

    /** 存量已完成：仅增量模板（用户约定：存量跑完只报增量） */
    private String renderIncremental(EmbeddingStatsReportEvent event, String window) {
        long newEmbedded = Math.max(event.getNewArticleCount() - event.getNewPendingCount(), 0);
        return "cls_article 向量化统计报告（" + window + "，北京时间）\n\n"
                + "存量回填已全部完成，累计 " + event.getTotalArticles() + " 条"
                + "（永久失败 " + event.getFailedCount() + " 条）。\n\n"
                + "一、本周期增量数据\n"
                + "新增电报：" + event.getNewArticleCount() + " 条\n"
                + "已嵌入：" + newEmbedded + " 条 / 待处理：" + event.getNewPendingCount() + " 条\n"
                + "本周期完成嵌入：" + event.getEmbeddedInWindow() + " 条\n\n"
                + "（存量已全部向量化，本报告此后仅统计增量数据）";
    }
}
