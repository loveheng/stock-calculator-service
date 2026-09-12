package com.zzh.stock_calculator.data.cls;

import com.rabbitmq.client.Channel;
import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stockcalc.contract.message.ClsArticlePayload;
import com.zzh.stockcalc.contract.message.ClsHistoryReport;
import com.zzh.stockcalc.contract.message.HistorySyncTask;
import com.zzh.stock_calculator.data.mq.ResultPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * CLS 历史补录执行器（设计文档 §3.2/§4.3，task.history.sync 单发单收，collector 角色）：
 * 主服务管理端点（/api/admin/sync/history/start）下发区间任务，本类滚动窗口拉取
 * （自 startTime 向 endTime 游标推进），逐条解析为契约 DTO 经 result.cls.article 上行，
 * 主服务幂等入库承担去重（D3/D6）；执行完回 result.cls.history.report（日志级回执）。
 * <p>进程内无游标无状态（D2）：区间重发无害；连续 5 次空数据自动终止（源站真空期防护）；
 * 交易时段动态降频防风控（原 main HistoryClsDayTask 频控策略平移）。</p>
 * <p>失败语义：区间任务为运维触发的有界动作，异常记日志后 ack 丢弃（不进重试环），
 * 需要时由管理员重新触发。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "datasvc.collector", name = "enabled", havingValue = "true")
public class HistorySyncWorker {

    /** 批大小：与原 getRollData(rn=50) 一致 */
    private static final int BATCH_SIZE = 50;

    /** 连续空数据终止阈值（原 HistoryClsDayTask 同款防护） */
    private static final int MAX_CONSECUTIVE_EMPTY = 5;

    private final ClsHttpService httpService;
    private final ResultPublisher resultPublisher;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = MqQueue.TASK_HISTORY_SYNC,
            containerFactory = "collectorControlListenerFactory")
    public void onMessage(Message message,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            MessageEnvelope envelope = objectMapper.readValue(body, MessageEnvelope.class);
            if (!MessageType.TASK_HISTORY_SYNC.equals(envelope.getType())) {
                log.info("skip unsupported task type={} messageId={}", envelope.getType(), envelope.getMessageId());
                return;
            }
            HistorySyncTask task = objectMapper.convertValue(envelope.getPayload(), HistorySyncTask.class);
            if (task == null || task.getStartTime() == null || task.getEndTime() == null) {
                log.warn("history sync task missing window, dropped, messageId={}", envelope.getMessageId());
                return;
            }
            runSync(task);
        } catch (Exception e) {
            log.error("history sync task failed, dropped (no retry ring)", e);
        } finally {
            try {
                channel.basicAck(deliveryTag, false);
            } catch (Exception ackError) {
                log.error("failed to ack history sync task, broker will redeliver", ackError);
            }
        }
    }

    /** 区间滚动拉取：游标自 startTime（较新）向 endTime（较旧）推进，批次内取最小 ctime */
    private void runSync(HistorySyncTask task) {
        long cursor = task.getStartTime();
        long target = task.getEndTime();
        int inserted = 0;
        int consecutiveEmpty = 0;
        log.info(">>> history sync start requestId={} cursor={} target={}", task.getRequestId(), cursor, target);

        while (cursor > target) {
            List<?> rollData;
            try {
                rollData = fetchRoll(cursor);
            } catch (Exception e) {
                log.error("history sync fetch error, cursor={}", cursor, e);
                if (++consecutiveEmpty >= MAX_CONSECUTIVE_EMPTY) {
                    log.error("history sync aborted after {} consecutive failures", consecutiveEmpty);
                    break;
                }
                sleepQuietly(backoffMillis(consecutiveEmpty));
                continue;
            }

            if (rollData == null || rollData.isEmpty()) {
                consecutiveEmpty++;
                log.warn("history sync empty batch, cursor={}, consecutive={}", cursor, consecutiveEmpty);
                if (consecutiveEmpty >= MAX_CONSECUTIVE_EMPTY) {
                    log.error("history sync aborted after {} consecutive empty batches", consecutiveEmpty);
                    break;
                }
                cursor -= 1; // 强制下移步进打破真空期
                sleepQuietly(backoffMillis(consecutiveEmpty));
                continue;
            }

            long minCtimeInBatch = cursor;
            for (Object item : rollData) {
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                try {
                    Map<String, Object> map = ClsValueUtil.coerceMap(raw);
                    long ctime = parseLong(map.get("ctime"));
                    if (ctime > 0 && ctime < minCtimeInBatch) {
                        minCtimeInBatch = ctime;
                    }
                    ClsArticlePayload payload = ClsArticleParser.parse(map);
                    if (payload == null) {
                        continue; // id 缺失，无法作为幂等锚点
                    }
                    resultPublisher.publish(MessageType.RESULT_CLS_ARTICLE, payload);
                    inserted++;
                } catch (Exception e) {
                    log.warn("history sync parse/publish failed, id={}", raw.get("id"), e);
                }
            }

            // 游标推进与防死锁（原 HistoryClsDayTask 同款）
            cursor = minCtimeInBatch >= cursor ? cursor - 1 : minCtimeInBatch;
            consecutiveEmpty = 0;
            sleepQuietly(dynamicSleepMillis());
        }

        log.info(">>> history sync end requestId={} inserted={} finalCursor={} target={}",
                task.getRequestId(), inserted, cursor, target);
        resultPublisher.publish(MessageType.RESULT_CLS_HISTORY_REPORT, ClsHistoryReport.builder()
                .requestId(task.getRequestId())
                .startTime(task.getStartTime())
                .endTime(task.getEndTime())
                .inserted(inserted)
                .build());
    }

    private List<?> fetchRoll(long cursor) {
        Map<String, Object> params = ClsApiParams.rollParams(cursor, 1, BATCH_SIZE);
        params.put("sign", ClsSignUtil.getSign(params));
        Map<String, Object> result = httpService.getForMap(ClsApiParams.ROLL_URL, params, ClsApiParams.header());
        if (result != null && result.get("data") instanceof Map<?, ?> dataMap
                && dataMap.get("roll_data") instanceof List<?> rollList) {
            return rollList;
        }
        return List.of();
    }

    /** 动态频控（原 HistoryClsDayTask 平移）：交易时段 8~15s / 午休 4~7s / 闲时 1.5~3s */
    private long dynamicSleepMillis() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        DayOfWeek dayOfWeek = now.getDayOfWeek();
        LocalTime time = now.toLocalTime();
        boolean isWeekend = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
        boolean isTradingHours = !isWeekend
                && time.isAfter(LocalTime.of(9, 15))
                && time.isBefore(LocalTime.of(15, 30));
        boolean isLunchBreak = !isWeekend
                && time.isAfter(LocalTime.of(11, 35))
                && time.isBefore(LocalTime.of(12, 55));
        if (isTradingHours && !isLunchBreak) {
            return ThreadLocalRandom.current().nextLong(8000, 15000);
        }
        if (isLunchBreak) {
            return ThreadLocalRandom.current().nextLong(4000, 7000);
        }
        return ThreadLocalRandom.current().nextLong(1500, 3000);
    }

    private long backoffMillis(int failureCount) {
        return Math.min(10000L * failureCount, 60000L);
    }

    private static long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
