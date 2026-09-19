package com.zzh.stock_calculator.data.worker;

import com.rabbitmq.client.Channel;
import com.zzh.stock_calculator.data.llm.LlmGatewayProperties;
import com.zzh.stock_calculator.data.mq.ResultPublisher;
import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqPolicy;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stockcalc.contract.message.KgExtractDonePayload;
import com.zzh.stockcalc.contract.message.KgExtractFailedPayload;
import com.zzh.stockcalc.contract.message.KgExtractTask;
import com.zzh.stockcalc.contract.message.KgExtraction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * worker 角色知识图谱抽取消费端（docs/ai-pipeline/cls-news-kg.md §8，D3 无状态抽取分界）：
 * 竞争消费 task.kg.extract.q（prefetch=2），Spring AI OpenAiChatModel + BeanOutputConverter
 * 结构化抽取（实体/关系/事件/归一化时间）→ KgExtractDonePayload 经 result.kg.done 上行。
 * worker 只算不存（D2），无锚点/融合责任（实体消解在 main 落库侧）。
 *
 * <p>失败分流（D10 三分类，EmbeddingErrorClassifier 同源分类；与 embedding worker 不同，
 * KG 契约有 failed 通道，失败回报后 ack——每日重发节奏下 30s 重试环无意义）：
 * PERMANENT（解析失败/空正文/未知兜底）/ TRANSIENT（IO/5xx/中断）→ failed 回报交主服务
 * fail_count 计次；RATE_LIMITED（429）→ failed 回报触发发布端熔断窗口；
 * 401/403 属整体凭据错误 → 直投 dead.q 停放不回报（状态行留 PENDING，修复后对账重发自愈）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "datasvc.worker",
    name = "enabled",
    havingValue = "true"
)
public class KgExtractWorker {

    /** 抽取规则（设计文档 §8 prompt 骨架；时间归一化基准由任务 payload 的 ctime 注入 user 消息） */
    private static final String SYSTEM_PROMPT = """
            你是财经新闻知识图谱抽取器，从《新闻联播》要闻汇编稿中抽取结构化知识。规则：
            1. 只抽正文明确提及的实体，禁止发明；实体类型限 STOCK/SUBJECT/ORG/PERSON/PLACE/POLICY/EVENT/OTHER
            2. 时间归一化：正文绝对日期优先；相对表述（昨日/上周）以文章发布时间为基准换算为 ISO-8601；
               无法确定时间时 time 留空且必须给 timeText 原文表述
            3. 标题日期仅作参考，不直接作为事件日期（汇编稿标题日期与联播播出日可能差一天）
            4. 谓词限受控词表：出台/发布/召开/签署/合作/任命/增长/下降/投资/扩大/禁止/推进/其他
            5. 收集实体别名（机构全称/简称/英文缩写/上市主体名），供下游字典对齐
            """;

    private final OpenAiChatModel chatModel;
    private final ResultPublisher resultPublisher;
    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    private final LlmGatewayProperties llmProperties;

    @RabbitListener(queues = MqQueue.TASK_KG_EXTRACT,
            containerFactory = "kgWorkerListenerFactory")
    public void onMessage(Message message,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        MessageEnvelope envelope;
        KgExtractTask task;
        try {
            envelope = objectMapper.readValue(body, MessageEnvelope.class);
            task = envelope.getPayload() == null
                    ? null
                    : objectMapper.convertValue(envelope.getPayload(), KgExtractTask.class);
        } catch (Exception e) {
            log.warn("kg task envelope unparsable, nacked to retry ring, body={}", brief(message), e);
            retryOrDead(message, channel, deliveryTag, e);
            return;
        }
        try {
            process(envelope, task);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            handleLlmFailure(envelope, task, message, channel, deliveryTag, e);
        }
    }

    /**
     * 抽取主流程：空正文按 PERMANENT 回报（源数据缺陷落终态，防对账每日空转）→
     * prompt（系统规则 + 标题/发布时间 ISO/正文 + BeanOutputConverter 格式约束）→
     * 结构化抽取 → 解析失败按 PERMANENT 回报（rawTail 留痕，lessons「LLM 网关」）→ 结果上行。
     */
    private void process(MessageEnvelope envelope, KgExtractTask task) {
        if (
            task == null ||
            task.getArticleId() == null ||
            task.getContent() == null ||
            task.getContent().isBlank()
        ) {
            log.warn(
                "kg task with unusable content, report PERMANENT, articleId={}",
                task == null ? null : task.getArticleId()
            );
            reportFailure(
                envelope,
                task,
                "blank or missing content",
                KgExtractFailedPayload.ERROR_KIND_PERMANENT
            );
            return;
        }
        BeanOutputConverter<KgExtraction> converter = new BeanOutputConverter<>(KgExtraction.class);
        String publishTime = task.getCtime() == null
                ? "unknown"
                : OffsetDateTime.ofInstant(Instant.ofEpochSecond(task.getCtime()), ZoneId.systemDefault())
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String userMessage = "【标题】" + safe(task.getTitle())
            + "\n【发布时间】" + publishTime
            + "\n【正文】\n" + task.getContent()
            + "\n\n" + converter.getFormat();
        ChatResponse response = chatModel.call(
            new Prompt(new SystemMessage(SYSTEM_PROMPT), new UserMessage(userMessage)));
        String text = response == null ||
            response.getResult() == null ||
            response.getResult().getOutput() == null
                ? null
                : response.getResult().getOutput().getText();
        KgExtraction extraction;
        try {
            extraction = converter.convert(text);
        } catch (Exception e) {
            log.warn("kg extraction unparsable, articleId={}, rawTail={}",
                task.getArticleId(), rawTail(text), e);
            reportFailure(
                envelope,
                task,
                "unparsable extraction: " + e.getMessage(),
                KgExtractFailedPayload.ERROR_KIND_PERMANENT
            );
            return;
        }
        if (extraction == null) {
            reportFailure(
                envelope,
                task,
                "empty extraction",
                KgExtractFailedPayload.ERROR_KIND_PERMANENT
            );
            return;
        }
        KgExtractDonePayload done = KgExtractDonePayload.builder()
            .articleId(task.getArticleId())
            .contentHash(task.getContentHash())
            .ctime(task.getCtime())
            .model(llmProperties.getModel())
            .extraction(extraction)
            .build();
        resultPublisher.publish(
            MessageType.RESULT_KG_DONE,
            done,
            MqPolicy.PRODUCER_WORKER,
            envelope.getTraceId()
        );
        log.info(
            "kg extracted, articleId={}, entities={}, relations={}, events={}, messageId={}",
            task.getArticleId(),
            sizeOf(extraction.getEntities()),
            sizeOf(extraction.getRelations()),
            sizeOf(extraction.getEvents()),
            envelope.getMessageId()
        );
    }

    /**
     * LLM 失败三分类（D10）：401/403 凭据整体错误 → 直投 dead.q 停放不回报（修复后
     * 对账重发自愈）；RATE_LIMITED/TRANSIENT/PERMANENT → failed 回报 + ack（主服务
     * fail_count 计次/终态判定/熔断窗口）。
     */
    private void handleLlmFailure(
        MessageEnvelope envelope,
        KgExtractTask task,
        Message message,
        Channel channel,
        long deliveryTag,
        Exception e
    ) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        try {
            if (EmbeddingErrorClassifier.isFatalAuth(e)) {
                rabbitTemplate.send(
                    MqExchange.DLX,
                    MqKey.DEAD_PREFIX + safeType(message),
                    message
                );
                channel.basicAck(deliveryTag, false);
                log.error("kg task fatal auth error, parked in dead.q for replay, err={}", e.getMessage());
                return;
            }
            EmbeddingErrorClassifier.ErrorType type = EmbeddingErrorClassifier.classify(e);
            String kind = switch (type) {
                case RATE_LIMITED -> KgExtractFailedPayload.ERROR_KIND_RATE_LIMITED;
                case TRANSIENT -> KgExtractFailedPayload.ERROR_KIND_TRANSIENT;
                case PERMANENT -> KgExtractFailedPayload.ERROR_KIND_PERMANENT;
            };
            log.warn(
                "kg task llm failure ({}), articleId={}, err={}",
                type,
                task == null ? null : task.getArticleId(),
                e.getMessage()
            );
            reportFailure(envelope, task, e.getMessage(), kind);
            channel.basicAck(deliveryTag, false);
        } catch (Exception ackError) {
            log.error("failed to ack, broker will redeliver", ackError);
        }
    }

    /** 失败回报（failReason/message ≤200 截断防超列宽；日志红线：不含正文） */
    private void reportFailure(
        MessageEnvelope envelope,
        KgExtractTask task,
        String reason,
        String kind
    ) {
        KgExtractFailedPayload payload = KgExtractFailedPayload.builder()
            .articleId(task == null ? null : task.getArticleId())
            .contentHash(task == null ? null : task.getContentHash())
            .failReason(truncate(reason))
            .errorKind(kind)
            .message(truncate(reason))
            .build();
        resultPublisher.publish(
            MessageType.RESULT_KG_FAILED,
            payload,
            MqPolicy.PRODUCER_WORKER,
            envelope == null ? null : envelope.getTraceId()
        );
    }

    /**
     * 毒消息/基础设施失败统一路径（与 embedding worker 同款，§4.2）：requeue=false →
     * 经 DLX 进 retry 队列 30s 回原队列；x-death 达 MAX_DELIVERY_ATTEMPTS 后投 dead.q 停放。
     */
    private void retryOrDead(Message message, Channel channel, long deliveryTag, Exception cause) {
        int attempts = deathCount(message) + 1;
        try {
            if (attempts >= MqPolicy.MAX_DELIVERY_ATTEMPTS) {
                rabbitTemplate.send(
                    MqExchange.DLX,
                    MqKey.DEAD_PREFIX + safeType(message),
                    message
                );
                channel.basicAck(deliveryTag, false);
                log.error("kg task moved to dead.q, attempts={}, body={}", attempts, brief(message), cause);
            } else {
                channel.basicNack(deliveryTag, false, false);
                log.warn("kg task nacked to retry ring, attempts={}, err={}", attempts, cause.getMessage());
            }
        } catch (Exception ackError) {
            log.error("failed to ack/nack, broker will redeliver", ackError);
        }
    }

    private String safeType(Message message) {
        Object type = message.getMessageProperties().getHeaders().get("type");
        if (type == null) {
            // 发布端以 props.setType（AMQP basic.type 属性）标记消息类型，
            // DLX 转发保留该属性但不进 headers map，需双通道取值
            type = message.getMessageProperties().getType();
        }
        return type == null ? "unknown" : String.valueOf(type);
    }

    private String brief(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        return body.length() <= 200 ? body : body.substring(0, 200) + "...";
    }

    /** x-death 累计计数（各队列 entry 的 count 求和） */
    @SuppressWarnings("unchecked")
    private int deathCount(Message message) {
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

    /** 解析失败日志带原文尾段（EOF 类异常看尾段才能定位，lessons「LLM 网关」） */
    private static String rawTail(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 200 ? text : "..." + text.substring(text.length() - 200);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 200 ? value : value.substring(0, 200);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static int sizeOf(java.util.Collection<?> collection) {
        return collection == null ? 0 : collection.size();
    }

}
