package com.zzh.stock_calculator.auth.service;

import com.zzh.stock_calculator.monitor.PipelineAlertEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 管线告警邮件（monitor 域巡检事件的 auth 侧适配器，EmbeddingStatsMailListener 同款模式）。
 * 依赖方向 auth → monitor 基包 API（Modulith 合规，无环）；
 * 收件人经 PIPELINE_ALERT_EMAIL 注入，未配置即跳过（告警不落库不重试，冷却由发布端承担）；
 * MailService 仅在 app.auth.enabled=true 装配，缺失时 ObjectProvider 兜底跳过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineAlertMailListener {

    private final ObjectProvider<MailService> mailServiceProvider;

    /** 收件人（.env 注入 PIPELINE_ALERT_EMAIL；为空时不发送） */
    @Value("${PIPELINE_ALERT_EMAIL:}")
    private String alertEmail;

    @EventListener
    public void onPipelineAlert(PipelineAlertEvent event) {
        if (alertEmail == null || alertEmail.isBlank()) {
            log.info("pipeline alert ready, PIPELINE_ALERT_EMAIL not set, skip mail, kind={}", event.getKind());
            return;
        }
        MailService mailService = mailServiceProvider.getIfAvailable();
        if (mailService == null) {
            log.warn("pipeline alert ready but mail service unavailable (app.auth.enabled=false), skip mail");
            return;
        }
        try {
            mailService.sendText(alertEmail, event.getSubject(), event.getBody());
        } catch (Exception e) {
            log.warn("pipeline alert mail failed, email={}, kind={}", alertEmail, event.getKind(), e);
        }
    }
}
