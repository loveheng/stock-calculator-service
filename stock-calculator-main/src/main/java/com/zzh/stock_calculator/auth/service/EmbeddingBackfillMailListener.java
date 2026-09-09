package com.zzh.stock_calculator.auth.service;

import com.zzh.stock_calculator.crawler.EmbeddingBackfillCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 回填完成通知邮件（crawler 完成事件的 auth 侧适配器）。
 *
 * <p>依赖方向 auth → crawler 基包 API（Modulith 合规，无环）。收件人经
 * EMBEDDING_NOTIFY_EMAIL 注入，未配置即跳过；MailService 仅在 app.auth.enabled=true
 * 装配，缺失时 ObjectProvider 兜底跳过；发送失败仅 WARN，不影响回填任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingBackfillMailListener {

    private final ObjectProvider<MailService> mailServiceProvider;

    /** 收件人（.env 注入 EMBEDDING_NOTIFY_EMAIL；为空时不发送） */
    @Value("${EMBEDDING_NOTIFY_EMAIL:}")
    private String notifyEmail;

    @EventListener
    public void onBackfillCompleted(EmbeddingBackfillCompletedEvent event) {
        if (notifyEmail == null || notifyEmail.isBlank()) {
            log.info("embedding backfill completed, EMBEDDING_NOTIFY_EMAIL not set, skip notify mail");
            return;
        }
        MailService mailService = mailServiceProvider.getIfAvailable();
        if (mailService == null) {
            log.warn("embedding backfill completed but mail service unavailable (app.auth.enabled=false), skip notify mail");
            return;
        }
        long unprocessed = Math.max(event.getTotalArticles() - event.getDoneCount() - event.getFailedCount(), 0);
        long minutes = Math.max(event.getElapsedMs(), 0) / 60000;
        String body = "cls_article 向量回填已完成。\n\n"
                + "文章总数：" + event.getTotalArticles() + "\n"
                + "已向量化：" + event.getDoneCount() + "\n"
                + "永久失败：" + event.getFailedCount() + "\n"
                + "其他未处理：" + unprocessed + "\n"
                + "本轮耗时：约 " + minutes + " 分钟\n\n"
                + "（本邮件由回填任务自动发送，进程生命周期内仅此一封；\n"
                + "永久失败明细见 cls_article_embedding 表 status='FAILED' 行的 error 列）";
        try {
            mailService.sendText(notifyEmail, "【股票计算助手】向量回填完成通知", body);
        } catch (Exception e) {
            log.warn("embedding backfill notify mail failed, email={}", notifyEmail, e);
        }
    }
}
