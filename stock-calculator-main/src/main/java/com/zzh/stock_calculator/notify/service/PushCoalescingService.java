package com.zzh.stock_calculator.notify.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 推送合并窗口（docs/alert/design.md §五补充：同用户短窗去抖，替代逐条轰炸）。
 *
 * <p>定位：PushDeliveryService 之前的缓冲层——同用户在窗口内（默认 30s）到达的 N 条消息
 * 落库 N 条（通知中心逐条可查，漏达兜底不缩水），但 Web Push 只发 1 条合并推送
 * （标题取首条、正文逐行列出）。判定循环批量触发（如大盘跳水多任务同轮进区间）时
 * 用户只收一条弹窗，点进通知中心看明细。</p>
 *
 * <p>实现取舍：JVM 内 ScheduledExecutorService 定时冲刷（窗口短、单实例语义、消息可容忍
 * 进程重启丢失——落库发生在冲刷时，重启只丢窗口内未投递的合并推送，拉取兜底通道不受影响，
 * 因为窗口内的落库也一并推迟）。多实例部署时各实例独立成窗，同用户可能收到
 * 每实例一条——当前单实例部署可接受（// UNCERTAIN: 多实例部署需改 MQ 延迟队列方案）。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "push.enabled", havingValue = "true")
public class PushCoalescingService {

    private final PushDeliveryService deliveryService;

    /** 合并窗口秒数（docs/alert/design.md：30s 默认，监控轮次触发密度量级） */
    @Value("${push.coalesce-window-seconds:30}")
    private long windowSeconds;

    private final Map<String, List<PushDeliveryService.PendingMessage>> buffer = new ConcurrentHashMap<>();
    private ScheduledExecutorService flusher;

    public PushCoalescingService(PushDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @PostConstruct
    void init() {
        flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "push-coalesce-flusher");
            t.setDaemon(true);
            return t;
        });
        // 固定步长冲刷（窗口粒度的延迟容忍：最多晚一个步长，不追求精确 per-entry 定时）
        flusher.scheduleWithFixedDelay(this::flush, windowSeconds, windowSeconds, TimeUnit.SECONDS);
        log.info("[push] 合并窗口就绪 {}s", windowSeconds);
    }

    @PreDestroy
    void shutdown() {
        flusher.shutdownNow();
        flush(); // 进程退出前尽力冲刷在途消息
    }

    /** 缓冲一条消息：立即落库可查性交给窗口冲刷统一做（见类注释取舍） */
    public void offer(String userId, String title, String body, String url) {
        buffer.computeIfAbsent(userId, k -> new ArrayList<>())
                .add(new PushDeliveryService.PendingMessage(title, body, url));
    }

    /** 冲刷：按用户合并成一条投递。单用户失败隔离，不影响其他用户 */
    void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<PushDeliveryService.PendingMessage>> e : buffer.entrySet()) {
            List<PushDeliveryService.PendingMessage> batch;
            synchronized (e.getValue()) {
                if (e.getValue().isEmpty()) {
                    continue;
                }
                batch = new ArrayList<>(e.getValue());
                e.getValue().clear();
            }
            try {
                deliveryService.pushMerged(e.getKey(), batch);
            } catch (Exception ex) {
                log.warn("[push] 合并投递失败 userId={} 条数={}: {}", e.getKey(), batch.size(), ex.getMessage());
            }
        }
    }

    /** 窗口秒数（配置自检用） */
    Duration window() {
        return Duration.ofSeconds(windowSeconds);
    }

    /** 供测试/管理口查看在途缓冲量 */
    int pendingUsers() {
        return buffer.size();
    }
}
