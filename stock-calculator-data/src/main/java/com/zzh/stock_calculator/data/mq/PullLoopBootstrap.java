package com.zzh.stock_calculator.data.mq;

import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stock_calculator.data.config.PullLoopProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 自循环启动种子（docs/pull-loop-unification-design.md §3.3）：ApplicationReady 后
 * 逐源检查延迟队列，空则投种子拉起循环。与 main 看门狗构成双保险——本类保证
 * 部署后第一时间起拉，看门狗兜底运行期种子断绝；深度守卫保证两处补种不产生双种子。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "datasvc.collector", name = "enabled", havingValue = "true")
public class PullLoopBootstrap {

    private final RabbitTemplate rabbitTemplate;
    private final PullConfigCache configCache;
    private final PullLoopProperties props;

    @EventListener(ApplicationReadyEvent.class)
    public void seedIfEmpty() {
        seedSource(MqKey.TASK_CLS_PULL, MqQueue.TASK_CLS_PULL_DELAY,
                MqKey.TASK_CLS_PULL_DELAY, props.getCls().isEnabled(), props.getCls().getTtlMs());
        seedSource(MqKey.TASK_ANNOUNCEMENT_COLLECT, MqQueue.TASK_ANNOUNCEMENT_COLLECT_DELAY,
                MqKey.TASK_ANNOUNCEMENT_COLLECT_DELAY,
                props.getAnnouncement().isEnabled(), props.getAnnouncement().getTtlMs());
    }

    private void seedSource(String taskCode, String delayQueue, String delayKey,
                            boolean defaultEnabled, long defaultTtlMs) {
        try {
            PullConfigCache.EffectiveConfig config = configCache.resolve(taskCode, defaultEnabled, defaultTtlMs);
            if (!config.enabled()) {
                log.info("[Pull Loop] {} bootstrap 跳过（disabled）", taskCode);
                return;
            }
            com.rabbitmq.client.AMQP.Queue.DeclareOk state =
                    rabbitTemplate.execute(ch -> ch.queueDeclarePassive(delayQueue));
            if (state != null && state.getMessageCount() > 0) {
                log.info("[Pull Loop] {} bootstrap 跳过（延迟队列已有种子 depth={}）",
                        taskCode, state.getMessageCount());
                return;
            }
            rabbitTemplate.convertAndSend(MqExchange.TASKS, delayKey, "seed", message -> {
                message.getMessageProperties().setExpiration(String.valueOf(config.ttlMs()));
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            });
            log.info("[Pull Loop] {} bootstrap 已投种子 ttl={}ms", taskCode, config.ttlMs());
        } catch (Exception e) {
            log.error("[Pull Loop] {} bootstrap 失败（看门狗将兜底补种）: {}", taskCode, e.toString());
        }
    }
}
