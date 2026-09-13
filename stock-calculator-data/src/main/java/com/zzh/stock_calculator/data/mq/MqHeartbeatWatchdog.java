package com.zzh.stock_calculator.data.mq;

import com.zzh.stock_calculator.data.config.HeartbeatProperties;
import com.zzh.stockcalc.contract.MqQueue;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MQ 心跳 watchdog（多副本改造新增，所有角色/变体无条件装配）：独立线程池定时对
 * 已知队列做 passive declare 往返，检测 AMQP 连接假死。容器内 restart 策略只在进程
 * 退出时生效，死锁/半开 TCP/OOM 边缘挂起这类"活着但不消费"的状态必须靠主动退出自愈——
 * 连续失败达阈值即 System.exit，交由 restart: unless-stopped 拉起。
 * <p>线程模型：tick 用单线程 ScheduledExecutor（不占 Spring 共享调度池，防 hang 波及
 * collector cron）；探活单独线程池 + 硬超时（半开 TCP 下 declare-ok 可能永不返回），
 * 挂死的探活线程随连接消亡，泄漏有界。成功时原子刷新心跳文件 mtime，供镜像层
 * HEALTHCHECK（Dockerfile.worker，无 HTTP 端点变体）按文件年龄判活——HEALTHCHECK
 * 只提供可见性，自愈完全由本类退出驱动。</p>
 */
@Slf4j
@Component
public class MqHeartbeatWatchdog {

    private final RabbitTemplate rabbitTemplate;
    private final HeartbeatProperties props;

    private final ScheduledExecutorService tickPool = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mq-heartbeat-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService probePool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mq-heartbeat-probe");
        t.setDaemon(true);
        return t;
    });

    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    public MqHeartbeatWatchdog(RabbitTemplate rabbitTemplate, HeartbeatProperties props) {
        this.rabbitTemplate = rabbitTemplate;
        this.props = props;
    }

    @PostConstruct
    public void start() {
        if (!props.isEnabled()) {
            log.info("MQ 心跳 watchdog 已禁用（datasvc.heartbeat.enabled=false）");
            return;
        }
        // 首跳提前到 10s：尽早验证 RABBIT_* 配置可达性（配错 3 分钟内显性退出而非静默）
        long initialDelayMs = Math.min(props.getIntervalMs(), 10_000L);
        tickPool.scheduleWithFixedDelay(this::tick, initialDelayMs, props.getIntervalMs(), TimeUnit.MILLISECONDS);
        log.info("MQ 心跳 watchdog 已启动：interval={}ms timeout={}ms threshold={} file={}",
                props.getIntervalMs(), props.getTimeoutMs(), props.getFailureThreshold(), props.getFile());
    }

    @PreDestroy
    public void stop() {
        tickPool.shutdownNow();
        probePool.shutdownNow();
    }

    private void tick() {
        try {
            Future<Object> probe = probePool.submit(() -> rabbitTemplate.execute(channel -> {
                channel.queueDeclarePassive(MqQueue.TASK_ANNOUNCEMENT_PROCESS);
                return null;
            }));
            probe.get(props.getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            int failures = consecutiveFailures.incrementAndGet();
            log.warn("MQ 心跳失败 {}/{}：{}", failures, props.getFailureThreshold(), e.toString());
            if (failures >= props.getFailureThreshold()) {
                log.error("MQ 连续 {} 次心跳失败，判定连接假死，进程退出交由容器重启策略自愈", failures);
                System.exit(1);
            }
            return;
        }
        int prevFailures = consecutiveFailures.getAndSet(0);
        if (prevFailures > 0) {
            log.info("MQ 心跳恢复（此前连续失败 {} 次）", prevFailures);
        }
        touchHeartbeatFile();
    }

    /** 刷新心跳文件 mtime（原子性对 mtime 读者足够）；失败仅影响探活可见性，不参与失败计数 */
    private void touchHeartbeatFile() {
        try {
            Path file = Path.of(props.getFile());
            if (Files.notExists(file)) {
                Files.createFile(file);
            }
            Files.setLastModifiedTime(file, FileTime.from(Instant.now()));
        } catch (Exception e) {
            log.warn("心跳文件更新失败（仅影响容器探活可见性）: {}", e.toString());
        }
    }
}
