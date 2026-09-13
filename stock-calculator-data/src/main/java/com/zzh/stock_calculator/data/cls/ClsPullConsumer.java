package com.zzh.stock_calculator.data.cls;

import com.rabbitmq.client.Channel;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stock_calculator.data.config.PullLoopProperties;
import com.zzh.stock_calculator.data.mq.PullLoopRenewer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * CLS 电报常态拉取消费者（docs/pull-loop-unification-design.md，原 ClsPullTask 的
 * 任务化改造）：消费自循环工作队列 task.cls.pull.q 执行一轮拉取，finally 中续种 +
 * 手动 ack（顺序 = 拉取 → 续种 → ack，L3：崩溃重投自愈，种子丢失窗口最小化）。
 * <p>恒 ack 语义：单轮失败不 nack——下一轮续种照常，retry 环不适用（工作队列无 DLX）。
 * 仅 collector 角色装配；worker 变体无本消费者（D4 采集单副本语义保留）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "datasvc.collector", name = "enabled", havingValue = "true")
public class ClsPullConsumer {

    private final ClsCollectorService collectorService;
    private final PullLoopRenewer renewer;
    private final PullLoopProperties props;

    @RabbitListener(queues = MqQueue.TASK_CLS_PULL,
            containerFactory = "collectorControlListenerFactory")
    public void onPull(Message message,
                       Channel channel,
                       @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            collectorService.pullAndPublish();
        } catch (Exception e) {
            log.warn("CLS pull 本轮失败（下一轮续种照常）: {}", e.toString());
        } finally {
            renewer.renew(MqKey.TASK_CLS_PULL, MqQueue.TASK_CLS_PULL_DELAY,
                    MqKey.TASK_CLS_PULL_DELAY,
                    props.getCls().isEnabled(), props.getCls().getTtlMs());
            try {
                channel.basicAck(deliveryTag, false);
            } catch (Exception ackError) {
                log.error("failed to ack cls pull task", ackError);
            }
        }
    }
}
