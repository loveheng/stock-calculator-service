package com.zzh.stock_calculator.data.mq;

import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.message.PullHeartbeatPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 自循环续种器（docs/pull-loop-unification-design.md §3.3 步骤 3-5）：深度守卫 →
 * 续种（per-message TTL）→ 心跳回报。两个拉取消费者（CLS/公告）共用。
 * <p>任何异常只落日志不上抛——循环存活优先，种子断绝由 main 看门狗兜底补种；
 * 深度守卫是唯一防双种子机制（SAC 仅串行不降噪，见设计文档 L2），续期日志是
 * 黑盒排障的入口（设计补充点 1）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "datasvc.collector", name = "enabled", havingValue = "true")
public class PullLoopRenewer {

    private final RabbitTemplate rabbitTemplate;
    private final ResultPublisher resultPublisher;
    private final PullConfigCache configCache;

    /**
     * 续种流程。taskCode 同时是 work routing key 与配置/心跳的源标识；
     * delayKey 为种子发布 routing key（到期后 DLX 改写为 taskCode 进工作队列）。
     */
    public void renew(String taskCode, String delayQueue, String delayKey,
                      boolean defaultEnabled, long defaultTtlMs) {
        PullConfigCache.EffectiveConfig config = configCache.resolve(taskCode, defaultEnabled, defaultTtlMs);
        if (!config.enabled()) {
            log.info("[Pull Loop] {} disabled，跳过续种（看门狗同步停补种，恢复延迟 ≤ 补种周期）", taskCode);
            reportHeartbeat(taskCode, 0, 0);
            return;
        }
        int depth = probeDepth(taskCode, delayQueue);
        if (depth < 0) {
            return;
        }
        if (depth > 0) {
            log.info("[Pull Loop] {} 深度={} >0，守卫跳过续种（防双种子）", taskCode, depth);
            reportHeartbeat(taskCode, depth, 0);
            return;
        }
        long appliedTtl;
        try {
            rabbitTemplate.convertAndSend(MqExchange.TASKS, delayKey, "seed", message -> {
                message.getMessageProperties().setExpiration(String.valueOf(config.ttlMs()));
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            });
            appliedTtl = config.ttlMs();
            log.info("[Pull Loop] {} 已续种 depth=0 ttl={}ms", taskCode, config.ttlMs());
        } catch (Exception e) {
            log.error("[Pull Loop] {} 续种投递失败（种子断绝风险，看门狗将兜底补种）: {}", taskCode, e.toString());
            reportHeartbeat(taskCode, depth, 0);
            return;
        }
        reportHeartbeat(taskCode, depth, appliedTtl);
    }

    /** passive declare 读延迟队列深度；-1 = 探针失败（broker 不可达等） */
    private int probeDepth(String taskCode, String delayQueue) {
        try {
            com.rabbitmq.client.AMQP.Queue.DeclareOk state =
                    rabbitTemplate.execute(ch -> ch.queueDeclarePassive(delayQueue));
            return state == null ? -1 : state.getMessageCount();
        } catch (Exception e) {
            log.error("[Pull Loop] {} 深度探针失败（种子断绝风险，看门狗将兜底补种）: {}", taskCode, e.toString());
            return -1;
        }
    }

    /** 心跳回报（观测信号非控制信号，失败不影响循环，设计不变量 3） */
    private void reportHeartbeat(String taskCode, int depth, long appliedTtl) {
        try {
            resultPublisher.publish(MessageType.RESULT_PULL_HEARTBEAT, PullHeartbeatPayload.builder()
                    .taskCode(taskCode)
                    .depth(depth)
                    .appliedTtlMs(appliedTtl)
                    .renewedAt(System.currentTimeMillis())
                    .build());
        } catch (Exception e) {
            log.warn("[Pull Loop] {} 心跳回报失败（不影响循环）: {}", taskCode, e.toString());
        }
    }
}
