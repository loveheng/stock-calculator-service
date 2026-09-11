package com.zzh.stock_calculator.data.worker;

import com.rabbitmq.client.Channel;
import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqPolicy;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stockcalc.contract.message.EmbeddingComputeResult;
import com.zzh.stockcalc.contract.message.EmbeddingComputeTask;
import com.zzh.stock_calculator.data.config.WorkerProperties;
import com.zzh.stock_calculator.data.mq.ResultPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * worker 角色向量化计算消费端（设计文档 §5，阶段 3 任务 5）：竞争消费
 * task.embedding.compute.q（prefetch=8 为副本伸缩单位，多副本自然分摊），
 * 每实例限流后调 CF 计算 → EmbeddingComputeResult 经 result.embedding.done 上行
 * （PRODUCER_WORKER 角色标识 + 任务信封 traceId 透传）。worker 只算不存（D2），
 * ack 即完账，无任何 DB 依赖。
 *
 * <p>失败分流（§4.2/§6.1，EmbeddingErrorClassifier 三分类）：
 * 429/PERMANENT → ack 丢弃不回报（契约无 embedding failed 通道，PENDING 状态行
 * 留给主服务对账器次日续发）；TRANSIENT → nack 进 TTL 重试环 30s 自愈，x-death 达
 * MAX_DELIVERY_ATTEMPTS 投 dead.q 停放；401/403 属整体凭据错误 → 直投 dead.q
 * （修复后可人工重放）。信封/payload 解析失败属毒消息 → 与主服务消费端同款
 * 重试环语义（不计业务状态）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "datasvc.worker", name = "enabled", havingValue = "true")
public class EmbeddingComputeWorker {

    private final EmbeddingModel embeddingModel;
    private final ResultPublisher resultPublisher;
    private final ObjectMapper objectMapper;
    private final EmbeddingRateLimiter rateLimiter;
    private final WorkerProperties properties;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = MqQueue.TASK_EMBEDDING_COMPUTE,
            containerFactory = "embeddingWorkerListenerFactory")
    public void onMessage(Message message,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        MessageEnvelope envelope;
        EmbeddingComputeTask task;
        try {
            envelope = objectMapper.readValue(body, MessageEnvelope.class);
            task = toTask(envelope);
        } catch (Exception e) {
            log.warn("embedding task envelope unparsable, nacked to retry ring, body={}", brief(message), e);
            retryOrDead(message, channel, deliveryTag, e);
            return;
        }
        try {
            process(envelope, task);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            handleComputeFailure(message, channel, deliveryTag, e);
        }
    }

    private EmbeddingComputeTask toTask(MessageEnvelope envelope) {
        if (envelope.getPayload() == null) {
            return null;
        }
        return objectMapper.convertValue(envelope.getPayload(), EmbeddingComputeTask.class);
    }

    /**
     * 计算主流程：kind 门控（阶段 3 仅 cls_article，announcement 随阶段 4 接入）→
     * 空文本丢弃 → 限流 → CF 计算 → 空结果按 PERMANENT 抛弃 → 组装结果上行。
     * 业务性跳过（未知 kind/空文本）返回即 ack；其余异常抛出交失败分流。
     */
    private void process(MessageEnvelope envelope, EmbeddingComputeTask task) throws InterruptedException {
        if (task == null || !EmbeddingComputeTask.KIND_CLS_ARTICLE.equals(task.getKind())) {
            log.warn("skip embedding task with unsupported kind={}, messageId={}",
                    task == null ? null : task.getKind(), envelope.getMessageId());
            return;
        }
        if (task.getText() == null || task.getText().isBlank()) {
            log.warn("skip embedding task with blank text, refId={}, messageId={}",
                    task.getRefId(), envelope.getMessageId());
            return;
        }
        rateLimiter.acquire();
        EmbeddingResponse response = embeddingModel.embedForResponse(List.of(task.getText()));
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            throw new IllegalStateException("empty embedding response, refId=" + task.getRefId());
        }
        float[] output = response.getResults().get(0).getOutput();
        if (output == null || output.length == 0) {
            throw new IllegalStateException("empty embedding output, refId=" + task.getRefId());
        }
        EmbeddingComputeResult result = EmbeddingComputeResult.builder()
                .kind(task.getKind())
                .refId(task.getRefId())
                .model(properties.getEmbedding().getModel())
                .dims(output.length)
                .vector(toVector(output))
                .tokensUsed(totalTokens(response))
                .build();
        resultPublisher.publish(MessageType.RESULT_EMBEDDING_DONE, result,
                MqPolicy.PRODUCER_WORKER, envelope.getTraceId());
        log.info("embedding computed, refId={}, dims={}, tokensUsed={}, messageId={}",
                task.getRefId(), output.length, result.getTokensUsed(), envelope.getMessageId());
    }

    private List<Float> toVector(float[] output) {
        List<Float> vector = new ArrayList<>(output.length);
        for (float v : output) {
            vector.add(v);
        }
        return vector;
    }

    /** usage 缺失容忍：CF 垫片已回填合成 Usage(0,0)，异常路径仍可能拿不到，置 null 由主服务容错 */
    private Long totalTokens(EmbeddingResponse response) {
        if (response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return null;
        }
        Integer total = response.getMetadata().getUsage().getTotalTokens();
        return total == null ? null : total.longValue();
    }

    /**
     * 计算失败分流（§6.1）：401/403 凭据整体错误 → 直投 dead.q 停放（修复后可重放）；
     * TRANSIENT → 重试环（达限 dead.q）；429/PERMANENT → ack 丢弃不占重试环，
     * PENDING 状态行由主服务对账器兜底续发（D7：契约无 embedding failed 回报通道）。
     */
    private void handleComputeFailure(Message message, Channel channel, long deliveryTag, Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        EmbeddingErrorClassifier.ErrorType type = EmbeddingErrorClassifier.classify(e);
        try {
            if (EmbeddingErrorClassifier.isFatalAuth(e)) {
                rabbitTemplate.send(MqExchange.DLX, MqKey.DEAD_PREFIX + safeType(message), message);
                channel.basicAck(deliveryTag, false);
                log.error("embedding task fatal auth error, parked in dead.q for replay, body={}", brief(message), e);
                return;
            }
            if (type == EmbeddingErrorClassifier.ErrorType.TRANSIENT) {
                retryOrDead(message, channel, deliveryTag, e);
                return;
            }
            channel.basicAck(deliveryTag, false);
            log.warn("embedding task dropped ({}), PENDING row left for reconciliation, err={}",
                    type, e.getMessage());
        } catch (Exception ackError) {
            log.error("failed to ack, broker will redeliver", ackError);
        }
    }

    /**
     * 毒消息/基础设施失败统一路径（与主服务消费端同款，§4.2）：requeue=false → 经 DLX
     * 进 retry 队列 30s 回原队列；x-death 达 MAX_DELIVERY_ATTEMPTS 后投 dead.q 停放。
     */
    private void retryOrDead(Message message, Channel channel, long deliveryTag, Exception cause) {
        int attempts = deathCount(message) + 1;
        try {
            if (attempts >= MqPolicy.MAX_DELIVERY_ATTEMPTS) {
                rabbitTemplate.send(MqExchange.DLX, MqKey.DEAD_PREFIX + safeType(message), message);
                channel.basicAck(deliveryTag, false);
                log.error("embedding task moved to dead.q, attempts={}, body={}", attempts, brief(message), cause);
            } else {
                channel.basicNack(deliveryTag, false, false);
                log.warn("embedding task nacked to retry ring, attempts={}, err={}", attempts, cause.getMessage());
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
}
