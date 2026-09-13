package com.zzh.stock_calculator.crawler.mq;

import com.rabbitmq.client.Channel;
import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqPolicy;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stockcalc.contract.message.AnnouncementCollectedPayload;
import com.zzh.stockcalc.contract.message.AnnouncementDonePayload;
import com.zzh.stockcalc.contract.message.AnnouncementFailedPayload;
import com.zzh.stockcalc.contract.message.ArticleIngestedPayload;
import com.zzh.stockcalc.contract.message.ClsArticleDto;
import com.zzh.stockcalc.contract.message.PullHeartbeatPayload;
import com.zzh.stock_calculator.monitor.PullHeartbeatEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.zzh.stockcalc.contract.message.ClsArticlePayload;
import com.zzh.stockcalc.contract.message.ClsStockDict;
import com.zzh.stockcalc.contract.message.ClsStockLink;
import com.zzh.stockcalc.contract.message.ClsSubjectDict;
import com.zzh.stockcalc.contract.message.ClsSubjectLink;
import com.zzh.stockcalc.contract.message.EmbeddingComputeResult;
import com.zzh.stock_calculator.crawler.AnnouncementIngestApi;
import com.zzh.stock_calculator.crawler.embedding.service.EmbeddingResultService;
import com.zzh.stock_calculator.crawler.entity.ClsArticle;
import com.zzh.stock_calculator.crawler.entity.ClsArticleStock;
import com.zzh.stock_calculator.crawler.entity.ClsArticleSubject;
import com.zzh.stock_calculator.crawler.entity.ClsSubject;
import com.zzh.stock_calculator.crawler.entity.Stock;
import com.zzh.stock_calculator.crawler.service.ClsArticleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * result.* 消费端（阶段 1/3，设计文档 §3.2/§4.3）：信封还原 → 按 type 分发 →
 * result.cls.article：DTO 映射实体 → 复用 saveArticleWithRelations 事务写入
 * （existsById 幂等，重复投递无副作用）；result.embedding.done：委托
 * EmbeddingResultService 确定性 UUID 向量 upsert + 状态行 DONE 落账。
 * 手动 ack；基础设施失败进 TTL 重试环（不计业务状态），x-death 达限投 dead.q（§4.2）。
 * <p>@Lazy(false)：豁免全局 spring.main.lazy-initialization——无人注入的监听器 bean 若不
 * 强制实例化，@RabbitListener 端点永不注册（静默失效）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Lazy(false)
public class ClsArticleMqConsumer {

    private final ClsArticleService clsArticleService;
    private final EmbeddingResultService embeddingResultService;
    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    /** 公告摄取端口（announcement 域实现；ObjectProvider 防实现缺失阻启动） */
    private final ObjectProvider<AnnouncementIngestApi> announcementIngestProvider;
    /** 跨域事件通道（result.pull.heartbeat → monitor 落表，事件对象在 monitor 基包） */
    private final ApplicationEventPublisher eventPublisher;

    @RabbitListener(queues = MqQueue.RESULT_INGEST)
    public void onMessage(org.springframework.amqp.core.Message message,
                          Channel channel,
                          @org.springframework.messaging.handler.annotation.Header(
                                  org.springframework.amqp.support.AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            MessageEnvelope envelope = objectMapper.readValue(body, MessageEnvelope.class);
            dispatch(envelope);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            handleFailure(message, channel, deliveryTag, e);
        }
    }

    private void dispatch(MessageEnvelope envelope) {
        switch (envelope.getType() == null ? "" : envelope.getType()) {
            case MessageType.RESULT_CLS_ARTICLE -> handleClsArticle(envelope);
            case MessageType.RESULT_EMBEDDING_DONE -> handleEmbeddingDone(envelope);
            case MessageType.RESULT_ANNOUNCEMENT_COLLECTED -> handleAnnouncementCollected(envelope);
            case MessageType.RESULT_ANNOUNCEMENT_DONE -> handleAnnouncementDone(envelope);
            case MessageType.RESULT_ANNOUNCEMENT_FAILED -> handleAnnouncementFailed(envelope);
            case MessageType.RESULT_ARTICLE_INGESTED -> handleArticleIngested(envelope);
            case MessageType.RESULT_PULL_HEARTBEAT -> handlePullHeartbeat(envelope);
            case MessageType.RESULT_CLS_HISTORY_REPORT -> log.info(
                    "cls history report requestId={} window=[{}, {}] inserted={}",
                    historyReportField(envelope, "requestId"),
                    historyReportField(envelope, "startTime"),
                    historyReportField(envelope, "endTime"),
                    historyReportField(envelope, "inserted"));
            // 其余 result.* 类型按阶段逐步接入；先记录后丢弃，避免堆积
            default -> log.info("skip unsupported result type={} messageId={}",
                    envelope.getType(), envelope.getMessageId());
        }
    }

    /** 历史补录回执为日志级（§4.3 无需幂等）：直接读 payload 字段打印 */
    private Object historyReportField(MessageEnvelope envelope, String field) {
        return envelope.getPayload() instanceof Map<?, ?> payload ? payload.get(field) : null;
    }

    /** result.pull.heartbeat → 转交 monitor 域落表（事件对象在 monitor 基包，基包引用合规） */
    private void handlePullHeartbeat(MessageEnvelope envelope) {
        PullHeartbeatPayload payload =
                objectMapper.convertValue(envelope.getPayload(), PullHeartbeatPayload.class);
        if (payload == null || payload.getTaskCode() == null) {
            log.warn("pull heartbeat payload 缺失 messageId={}", envelope.getMessageId());
            return;
        }
        eventPublisher.publishEvent(PullHeartbeatEvent.builder()
                .taskCode(payload.getTaskCode())
                .depth(payload.getDepth())
                .appliedTtlMs(payload.getAppliedTtlMs())
                .renewedAt(payload.getRenewedAt())
                .build());
    }

    private void handleClsArticle(MessageEnvelope envelope) {
        if (!schemaSupported(envelope)) {
            log.error("unsupported schemaVersion={} type={} messageId={}",
                    envelope.getSchemaVersion(), envelope.getType(), envelope.getMessageId());
            return;
        }
        ClsArticlePayload payload = objectMapper.convertValue(envelope.getPayload(), ClsArticlePayload.class);
        if (payload == null || payload.getArticle() == null || payload.getArticle().getId() == null) {
            log.warn("cls article payload missing article.id, messageId={}", envelope.getMessageId());
            return;
        }

        ClsArticle article = toArticleEntity(payload.getArticle());
        boolean saved = clsArticleService.saveArticleWithRelations(
                article,
                toSubjectLinks(payload),
                toStockLinks(payload),
                toStockDicts(payload),
                toSubjectDicts(payload));

        if (saved) {
            log.info("mq ingested new cls article id={} messageId={}", article.getId(), envelope.getMessageId());
        } else {
            log.debug("mq ingest dedup skip article id={}", article.getId());
        }
    }

    /** 协议演进护栏：不兼容版本直接丢弃（dead 语义由协议契约保证，见 D9） */
    private boolean schemaSupported(MessageEnvelope envelope) {
        return envelope.getSchemaVersion() == MessageEnvelope.CURRENT_SCHEMA_VERSION;
    }

    /**
     * result.announcement.collected（阶段 4 任务 2）：collector 采集结果 → 端口幂等入库
     * （去重/超体积终态/PENDING/orgId 回填均在 announcement 域内）。载荷非法业务性
     * 跳过（ack 丢弃）；其余异常抛出 → handleFailure 重试环。
     */
    private void handleAnnouncementCollected(MessageEnvelope envelope) {
        if (!schemaSupported(envelope)) {
            log.error("unsupported schemaVersion={} type={} messageId={}",
                    envelope.getSchemaVersion(), envelope.getType(), envelope.getMessageId());
            return;
        }
        AnnouncementCollectedPayload payload =
                objectMapper.convertValue(envelope.getPayload(), AnnouncementCollectedPayload.class);
        AnnouncementIngestApi ingestApi = announcementIngestProvider.getIfAvailable();
        if (ingestApi == null) {
            log.error("announcement ingest api absent, dropped, messageId={}", envelope.getMessageId());
            return;
        }
        boolean ingested = ingestApi.ingestCollected(payload);
        if (ingested) {
            log.info("mq announcement collected ingested, announcementId={}, messageId={}",
                    payload == null ? null : payload.getAnnouncementId(), envelope.getMessageId());
        } else {
            log.debug("mq announcement collected skipped, messageId={}", envelope.getMessageId());
        }
    }

    /**
     * result.announcement.done（阶段 4 任务 3，D7 两段式）：worker 处理成果 → 端口落账
     * （content 溯源行 1:1 upsert（正文不落库，D5）+ summary → 二段向量化任务下发）。
     * 载荷非法/未知公告/终态行业务性跳过（ack 丢弃）；其余异常抛出 → handleFailure 重试环。
     */
    private void handleAnnouncementDone(MessageEnvelope envelope) {
        if (!schemaSupported(envelope)) {
            log.error("unsupported schemaVersion={} type={} messageId={}",
                    envelope.getSchemaVersion(), envelope.getType(), envelope.getMessageId());
            return;
        }
        AnnouncementDonePayload payload =
                objectMapper.convertValue(envelope.getPayload(), AnnouncementDonePayload.class);
        AnnouncementIngestApi ingestApi = announcementIngestProvider.getIfAvailable();
        if (ingestApi == null) {
            log.error("announcement ingest api absent, dropped, messageId={}", envelope.getMessageId());
            return;
        }
        boolean ingested = ingestApi.ingestDone(payload);
        if (ingested) {
            log.info("mq announcement done ingested, announcementId={}, messageId={}",
                    payload == null ? null : payload.getAnnouncementId(), envelope.getMessageId());
        } else {
            log.debug("mq announcement done skipped, messageId={}", envelope.getMessageId());
        }
    }

    /**
     * result.announcement.failed（阶段 4 任务 3，D7）：worker 错误分类回报 → 端口落账
     * （failCount 计次/终态判定，RATE_LIMITED 触发发布端熔断窗口）。
     * 载荷非法/未知公告/非 PENDING 行业务性跳过；其余异常抛出 → handleFailure 重试环。
     */
    private void handleAnnouncementFailed(MessageEnvelope envelope) {
        if (!schemaSupported(envelope)) {
            log.error("unsupported schemaVersion={} type={} messageId={}",
                    envelope.getSchemaVersion(), envelope.getType(), envelope.getMessageId());
            return;
        }
        AnnouncementFailedPayload payload =
                objectMapper.convertValue(envelope.getPayload(), AnnouncementFailedPayload.class);
        AnnouncementIngestApi ingestApi = announcementIngestProvider.getIfAvailable();
        if (ingestApi == null) {
            log.error("announcement ingest api absent, dropped, messageId={}", envelope.getMessageId());
            return;
        }
        boolean ingested = ingestApi.ingestFailed(payload);
        if (ingested) {
            log.info("mq announcement failed ingested, announcementId={}, messageId={}",
                    payload == null ? null : payload.getAnnouncementId(), envelope.getMessageId());
        } else {
            log.debug("mq announcement failed skipped, messageId={}", envelope.getMessageId());
        }
    }

    /**
     * result.embedding.done（阶段 3）：worker 回报的向量 → 确定性 UUID upsert +
     * 状态行 DONE。业务性跳过（未知 kind/维度不符/文章缺失等）由 service 返回 false
     * 后 ack 丢弃；基础设施异常抛出 → handleFailure 分流重试环。
     */
    private void handleEmbeddingDone(MessageEnvelope envelope) {
        if (!schemaSupported(envelope)) {
            log.error("unsupported schemaVersion={} type={} messageId={}",
                    envelope.getSchemaVersion(), envelope.getType(), envelope.getMessageId());
            return;
        }
        EmbeddingComputeResult payload =
                objectMapper.convertValue(envelope.getPayload(), EmbeddingComputeResult.class);
        if (payload == null) {
            log.warn("embedding result payload missing, messageId={}", envelope.getMessageId());
            return;
        }
        boolean applied = embeddingResultService.applyComputeResult(payload);
        if (applied) {
            log.info("mq embedding result ingested, refId={}, messageId={}",
                    payload.getRefId(), envelope.getMessageId());
        } else {
            log.debug("mq embedding result skipped, refId={}, messageId={}",
                    payload.getRefId(), envelope.getMessageId());
        }
    }

    /**
     * 失败分流（§4.2）：统一进 TTL 重试环（requeue=false → DLX → retry 队列，30s 回原队列），
     * x-death 达 MAX_DELIVERY_ATTEMPTS 后投 dead.q 停放。基础设施失败（DB 抖动）靠重试自愈，
     * 毒消息（解析失败）达限后隔离在 dead.q，均不占用业务状态（failCount 属任务链路，阶段 3/4 引入）。
     */
    /**
     * result.article.ingested（阶段 5）：通用 webhook 摄取文章 → 复用 CLS 电报入库链
     * （saveArticleWithRelations：existsById 幂等 + ArticleSavedEvent 触发向量化）。
     * articleId 由数据侧按契约 IngestArticleIds 确定性生成，重复推送幂等；
     * 载荷非法业务性跳过（ack 丢弃）。
     */
    private void handleArticleIngested(MessageEnvelope envelope) {
        if (!schemaSupported(envelope)) {
            log.error("unsupported schemaVersion={} type={} messageId={}",
                    envelope.getSchemaVersion(), envelope.getType(), envelope.getMessageId());
            return;
        }
        ArticleIngestedPayload payload = objectMapper.convertValue(
                envelope.getPayload(), ArticleIngestedPayload.class);
        if (payload == null || payload.getArticleId() == null
                || payload.getContent() == null || payload.getContent().isBlank()) {
            log.warn("article ingested payload invalid, messageId={}", envelope.getMessageId());
            return;
        }
        ClsArticleDto dto = ClsArticleDto.builder()
                .id(payload.getArticleId())
                .type(-1)
                .title(payload.getTitle())
                .brief(payload.getBrief())
                .content(payload.getContent())
                .ctime(payload.getPublishedAt() == null ? 0L : payload.getPublishedAt())
                .author(payload.getAuthor() == null ? "webhook:" + payload.getSource() : payload.getAuthor())
                .level("C")
                .build();
        boolean saved = clsArticleService.saveArticleWithRelations(
                toArticleEntity(dto), List.of(), List.of(), List.of(), List.of());
        log.info("article ingested source={} externalId={} articleId={} saved={}",
                payload.getSource(), payload.getExternalId(), payload.getArticleId(), saved);
    }

    private void handleFailure(org.springframework.amqp.core.Message message, Channel channel,
                               long deliveryTag, Exception e) {
        int attempts = deathCount(message) + 1;
        try {
            if (attempts >= MqPolicy.MAX_DELIVERY_ATTEMPTS) {
                rabbitTemplate.send(MqExchange.DLX,
                        MqKey.DEAD_PREFIX + safeType(message), message);
                channel.basicAck(deliveryTag, false);
                log.error("message moved to dead.q attempts={} body={}", attempts, brief(message), e);
            } else {
                // requeue=false → 经 DLX 进 retry 队列，TTL 后回原队列
                channel.basicNack(deliveryTag, false, false);
                log.warn("message nacked to retry ring attempts={} err={}", attempts, e.getMessage());
            }
        } catch (Exception ackError) {
            log.error("failed to ack/nack, broker will redeliver", ackError);
        }
    }

    private String safeType(org.springframework.amqp.core.Message message) {
        Object type = message.getMessageProperties().getHeaders().get("type");
        if (type == null) {
            // 发布端以 props.setType（AMQP basic.type 属性）标记消息类型，
            // DLX 转发保留该属性但不进 headers map，需双通道取值
            type = message.getMessageProperties().getType();
        }
        return type == null ? "unknown" : String.valueOf(type);
    }

    private String brief(org.springframework.amqp.core.Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        return body.length() <= 200 ? body : body.substring(0, 200) + "...";
    }

    /** x-death 累计计数（各队列 entry 的 count 求和） */
    @SuppressWarnings("unchecked")
    private int deathCount(org.springframework.amqp.core.Message message) {
        Object xDeath = message.getMessageProperties().getHeaders().get("x-death");
        if (!(xDeath instanceof List<?> entries)) {
            return 0;
        }
        int total = 0;
        for (Object o : entries) {
            if (o instanceof Map<?, ?> rawEntry) {
                Object count = ((Map<String, Object>) rawEntry).get("count");
                if (count instanceof Number n) {
                    total += n.intValue();
                }
            }
        }
        return total;
    }

    // ==================== DTO → 实体映射（对齐原 ClsDayTaskHelp 解析字段的默认值语义） ====================

    private ClsArticle toArticleEntity(ClsArticleDto a) {
        return ClsArticle.builder()
                .id(a.getId())
                .type(a.getType() == null ? -1 : a.getType())
                .title(a.getTitle())
                .brief(a.getBrief())
                .content(a.getContent())
                .ctime(a.getCtime() == null ? 0L : a.getCtime())
                .author(a.getAuthor() == null ? "" : a.getAuthor())
                .level(a.getLevel() == null ? "C" : a.getLevel())
                .images(a.getImages())
                .audioUrl(a.getAudioUrl())
                .build();
    }

    private List<ClsSubject> toSubjectDicts(ClsArticlePayload payload) {
        List<ClsSubjectDict> dicts = payload.getSubjectDicts();
        if (dicts == null || dicts.isEmpty()) {
            return List.of();
        }
        return dicts.stream().map(d -> ClsSubject.builder()
                .subjectId(d.getSubjectId())
                .subjectName(d.getSubjectName())
                .plateId(d.getPlateId())
                .channel(d.getChannel())
                .build()).toList();
    }

    private List<Stock> toStockDicts(ClsArticlePayload payload) {
        List<ClsStockDict> dicts = payload.getStockDicts();
        if (dicts == null || dicts.isEmpty()) {
            return List.of();
        }
        return dicts.stream().map(d -> Stock.builder()
                .stockId(d.getStockId())
                .name(d.getName())
                .oldName(d.getOldName() == null ? d.getName() : d.getOldName())
                .isStib(Boolean.TRUE.equals(d.getIsStib()))
                .build()).toList();
    }

    private List<ClsArticleSubject> toSubjectLinks(ClsArticlePayload payload) {
        List<ClsSubjectLink> links = payload.getSubjectLinks();
        if (links == null || links.isEmpty()) {
            return List.of();
        }
        return links.stream().map(l -> ClsArticleSubject.builder()
                .articleId(l.getArticleId())
                .subjectId(l.getSubjectId())
                .build()).toList();
    }

    private List<ClsArticleStock> toStockLinks(ClsArticlePayload payload) {
        List<ClsStockLink> links = payload.getStockLinks();
        if (links == null || links.isEmpty()) {
            return List.of();
        }
        return links.stream().map(l -> ClsArticleStock.builder()
                .articleId(l.getArticleId())
                .stockId(l.getStockId())
                .lastPrice(l.getLastPrice())
                .riseRange(l.getRiseRange())
                .build()).toList();
    }
}
