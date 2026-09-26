package com.zzh.stock_calculator.notify.service;

import com.zzh.stock_calculator.notify.entity.PushMessage;
import com.zzh.stock_calculator.notify.repository.PushMessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 推送合并窗口·多实例安全版（docs/alert/design.md §五：同用户短窗去抖，Redis ZSET 延迟窗实现）。
 *
 * <p>定位：PushDeliveryService 之前的缓冲层——同用户在窗口内（默认 30s）到达的 N 条消息
 * 落库 N 条（通知中心逐条可查，漏达兜底不缩水），但 Web Push 只发 1 条合并推送
 * （标题取首条 +「N 条合并」，正文逐行列出）。</p>
 *
 * <p>多实例语义（2026-09-26 定案，替代原 JVM 内缓冲）：缓冲放 Redis ZSET
 * {@code push:coalesce:z}——score=到点冲刷时刻，member=消息 JSON。各实例独立跑冲刷循环，
 * {@code ZPOPMIN} 原子弹出认领，同一消息只会被一个实例投递（不重复）；实例宕机丢在途的
 * 仅是宕机瞬间已认领未投递的一小批，其余消息被其他实例继续冲刷。Redis 不可用时降级为
 * 直投（逐条 pushToUser，退化为合并前行为，不丢消息）。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "push.enabled", havingValue = "true")
public class PushCoalescingService {

    /** Redis ZSET 键：score=到点冲刷时刻（epoch ms），member=消息 JSON */
    private static final String ZSET_KEY = "push:coalesce:z";

    /** 冲刷扫描步长（秒）：各实例独立扫描，窗口语义精度 ± 本步长 */
    private static final long SCAN_INTERVAL_SECONDS = 5;

    /** 单批认领上限（ZPOPMIN count）：单批内按用户聚组，避免超长阻塞 */
    private static final int POP_BATCH = 100;

    /** 单条消息最大重试次数：超过后降级逐条落库（拉取兜底仍达），Web Push 放弃 */
    private static final int MAX_RETRY = 3;

    /** 投递失败重试回退（秒） */
    private static final long RETRY_DELAY_SECONDS = 60;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final StringRedisTemplate redis;
    private final PushDeliveryService deliveryService;
    private final PushMessageRepository messageRepository;

    /** 合并窗口秒数（docs/alert/design.md：30s 默认，监控轮次触发密度量级） */
    @Value("${push.coalesce-window-seconds:30}")
    private long windowSeconds;

    private ScheduledExecutorService flusher;

    public PushCoalescingService(StringRedisTemplate redis,
                                 PushDeliveryService deliveryService,
                                 PushMessageRepository messageRepository) {
        this.redis = redis;
        this.deliveryService = deliveryService;
        this.messageRepository = messageRepository;
    }

    @PostConstruct
    void init() {
        flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "push-coalesce-flusher");
            t.setDaemon(true);
            return t;
        });
        flusher.scheduleWithFixedDelay(this::flushSafely, SCAN_INTERVAL_SECONDS,
                SCAN_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("[push] 合并窗口就绪（Redis ZSET 多实例版） window={}s scan={}s",
                windowSeconds, SCAN_INTERVAL_SECONDS);
    }

    @PreDestroy
    void shutdown() {
        flusher.shutdownNow();
        flushSafely(); // 进程退出前尽力冲刷在途消息
    }

    /**
     * 缓冲一条消息：ZADD score=now+window。Redis 不可用时降级直投
     * （// DEGRADE: 合并缓冲失效退化为逐条推送——MQ 消费链路活着说明 Rabbit 正常，
     * 仅 Redis 故障不影响投递能力；待核实 Redis 故障告警是否已有 monitor 域覆盖）。
     */
    public void offer(String userId, String title, String body, String url) {
        try {
            ObjectNode node = JSON.createObjectNode()
                    .put("uid", userId)
                    .put("t", title == null ? "" : title)
                    .put("b", body == null ? "" : body)
                    .put("l", url == null ? "" : url)
                    .put("ts", System.currentTimeMillis())
                    .put("r", 0);
            long flushAt = System.currentTimeMillis() + windowSeconds * 1000;
            redis.opsForZSet().add(ZSET_KEY, JSON.writeValueAsString(node), flushAt);
            // 空 set 被 Redis 自动回收；1h TTL 兜底清理被遗弃的残集
            redis.expire(ZSET_KEY, Duration.ofHours(1));
        } catch (Exception e) {
            log.warn("[DEGRADE] push-coalesce offer 失败，直投退化 userId={}: {}", userId, e.getMessage());
            deliveryService.pushToUser(userId, title, body, url);
        }
    }

    /** 冲刷入口：异常隔离（定时器不被一次失败杀死），下轮自然重试 */
    void flushSafely() {
        try {
            flush();
        } catch (Exception e) {
            log.warn("[push] 合并窗口冲刷异常: {}", e.getMessage());
        }
    }

    /**
     * 冲刷：ZPOPMIN 原子认领到期消息 → 按用户聚组 → 合并投递。
     * 循环直到最早到期项仍在未来（说明无到期消息）或弹空。
     */
    void flush() {
        long now = System.currentTimeMillis();
        while (true) {
            Set<ZSetOperations.TypedTuple<String>> popped =
                    redis.opsForZSet().popMin(ZSET_KEY, POP_BATCH);
            if (popped == null || popped.isEmpty()) {
                return;
            }
            List<String> due = new ArrayList<>();
            for (ZSetOperations.TypedTuple<String> tuple : popped) {
                Double score = tuple.getScore();
                if (score == null || score <= now) {
                    due.add(tuple.getValue());
                } else {
                    // 弹到了未来项（ZPOPMIN 只按分数序不筛时间）：原样放回并终止本轮
                    redis.opsForZSet().add(ZSET_KEY, tuple.getValue(), score);
                }
            }
            if (due.isEmpty()) {
                return;
            }
            deliverGrouped(due);
            if (popped.size() < POP_BATCH) {
                return;
            }
        }
    }

    /** 认领后待投递单元：消息 + 已重试次数（重试计数随 member 往返，超限降级落库） */
    private record CoalesceEntry(String userId, PushDeliveryService.PendingMessage msg, int retry) { }

    /** 解析认领消息并按用户聚组投递；单用户失败隔离 + 重试，不影响其他用户 */
    private void deliverGrouped(List<String> members) {
        Map<String, List<CoalesceEntry>> byUser = new LinkedHashMap<>();
        for (String member : members) {
            try {
                ObjectNode node = (ObjectNode) JSON.readTree(member);
                String uid = node.get("uid").asString();
                int retry = node.has("r") ? node.get("r").asInt() : 0;
                byUser.computeIfAbsent(uid, k -> new ArrayList<>())
                        .add(new CoalesceEntry(uid, new PushDeliveryService.PendingMessage(
                                node.get("t").asString(), node.get("b").asString(), node.get("l").asString()),
                                retry));
            } catch (Exception e) {
                log.warn("[push] 合并消息解析失败，丢弃: {}", e.getMessage());
            }
        }
        for (Map.Entry<String, List<CoalesceEntry>> e : byUser.entrySet()) {
            List<CoalesceEntry> batch = e.getValue();
            try {
                deliveryService.pushMerged(e.getKey(),
                        batch.stream().map(CoalesceEntry::msg).toList());
            } catch (Exception ex) {
                handleDeliveryFailure(batch, ex);
            }
        }
    }

    /** 投递失败：未超上限回 ZSET 延迟重试（计数 +1）；超上限或 Redis 回写失败降级逐条落库（拉取兜底仍达） */
    private void handleDeliveryFailure(List<CoalesceEntry> batch, Exception cause) {
        log.warn("[push] 合并投递失败 userId={} 条数={}: {}",
                batch.get(0).userId(), batch.size(), cause.getMessage());
        for (CoalesceEntry entry : batch) {
            int nextRetry = entry.retry() + 1;
            if (nextRetry > MAX_RETRY) {
                log.warn("[DEGRADE] 重试超限({})，落库兜底 userId={}", MAX_RETRY, entry.userId());
                messageRepository.save(PushMessage.builder()
                        .userId(entry.userId()).title(entry.msg().title())
                        .body(entry.msg().body()).url(entry.msg().url()).build());
                continue;
            }
            try {
                ObjectNode node = JSON.createObjectNode()
                        .put("uid", entry.userId()).put("t", entry.msg().title())
                        .put("b", entry.msg().body())
                        .put("l", entry.msg().url() == null ? "" : entry.msg().url())
                        .put("ts", System.currentTimeMillis()).put("r", nextRetry);
                redis.opsForZSet().add(ZSET_KEY, JSON.writeValueAsString(node),
                        System.currentTimeMillis() + RETRY_DELAY_SECONDS * 1000);
            } catch (Exception redisDown) {
                log.warn("[DEGRADE] 重试回写失败，落库兜底 userId={}: {}", entry.userId(), redisDown.getMessage());
                messageRepository.save(PushMessage.builder()
                        .userId(entry.userId()).title(entry.msg().title())
                        .body(entry.msg().body()).url(entry.msg().url()).build());
            }
        }
    }

    /** 窗口秒数（配置自检用） */
    Duration window() {
        return Duration.ofSeconds(windowSeconds);
    }

    /** 在途缓冲量（管理口/排查用） */
    long pendingCount() {
        Long size = redis.opsForZSet().zCard(ZSET_KEY);
        return size == null ? 0 : size;
    }
}
