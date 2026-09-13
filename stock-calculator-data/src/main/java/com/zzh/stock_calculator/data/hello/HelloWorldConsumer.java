package com.zzh.stock_calculator.data.hello;

import com.rabbitmq.client.Channel;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stockcalc.contract.message.PullHeartbeatPayload;
import com.zzh.stock_calculator.data.mq.ResultPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 日历型定时任务一次性消费者（docs/pull-loop-unification-design.md §8.3.4，首个接入：
 * 每日 07:00 Asia/Shanghai 打印 hello world）。与拉取消费者（ClsPullConsumer）的差异
 * 仅三点——无续种、无深度守卫、无 bootstrap：「钟」在 main 侧 pull_task_config 调度
 * 游标（L8/L9），本类只执行 + 心跳回报 + 手动 ack，对配置零依赖（L10）。
 * <p>collector 门控 + prefetch=1：串行消费，积压顺延执行不并发。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "datasvc.collector", name = "enabled", havingValue = "true")
public class HelloWorldConsumer {

    private final ResultPublisher resultPublisher;

    @RabbitListener(queues = MqQueue.TASK_HELLO_WORLD,
            containerFactory = "collectorControlListenerFactory")
    public void onTask(Message message,
                       Channel channel,
                       @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            log.info("Hello World! (calendar task {} fired at {})", MqKey.TASK_HELLO_WORLD, LocalDateTime.now());
            reportHeartbeat();
        } catch (Exception e) {
            log.warn("hello world 日历任务本轮失败（不重试，等下一日历点，L11）: {}", e.toString());
        } finally {
            try {
                channel.basicAck(deliveryTag, false);
            } catch (Exception ackError) {
                log.error("failed to ack hello world calendar task", ackError);
            }
        }
    }

    /** 心跳回报（观测信号非控制信号，设计不变量 3/8；appliedTtlMs=0 为 CALENDAR 行哨兵值） */
    private void reportHeartbeat() {
        try {
            resultPublisher.publish(MessageType.RESULT_PULL_HEARTBEAT, PullHeartbeatPayload.builder()
                    .taskCode(MqKey.TASK_HELLO_WORLD)
                    .depth(0)
                    .appliedTtlMs(0)
                    .renewedAt(System.currentTimeMillis())
                    .build());
        } catch (Exception e) {
            log.warn("hello world 心跳回报失败（不影响调度）: {}", e.toString());
        }
    }
}
