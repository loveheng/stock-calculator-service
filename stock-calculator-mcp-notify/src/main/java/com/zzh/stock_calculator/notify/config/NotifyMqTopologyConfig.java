package com.zzh.stock_calculator.notify.config;

import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqQueue;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * notify 侧 MQ 拓扑（docs/notify/design.md §4.2）：
 * - 消费半区：reminder.fire.q（种子到期死信）+ result.notify.capability.q（能力结果回流）；
 * - 发布半区：task.notify.capability / notify.push（发到既有 TASKS / RESULTS 交换机，交换机
 *   由 main/data 侧声明，此处只声明队列与绑定）；
 * - reminder.delay.q：classic、无消费者、无 x-message-ttl——TTL 逐条消息自带，
 *   DLX=TASKS + DLK=reminder.fire（与 pull-loop delay 队列同款自循环钟摆，N2）。
 * 交换机声明幂等（与 main/data 侧参数一致，重复声明为 no-op）。
 */
@Configuration
public class NotifyMqTopologyConfig {

    @Bean
    public AmqpAdmin amqpAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    public TopicExchange tasksExchange() {
        return new TopicExchange(MqExchange.TASKS);
    }

    @Bean
    public TopicExchange resultsExchange() {
        return new TopicExchange(MqExchange.RESULTS);
    }

    @Bean
    public TopicExchange dlxExchange() {
        return new TopicExchange(MqExchange.DLX);
    }

    /** 种子到期死信落地队列（quorum，redeliver + fired 唯一键幂等，§五宕机恢复语义） */
    @Bean
    public Queue reminderFireQueue() {
        return QueueBuilder.durable(MqQueue.REMINDER_FIRE)
                .quorum()
                .build();
    }

    /** 定时提醒种子队列：classic、无消费者、per-message TTL，到期 DLX 改写进 fire 队列 */
    @Bean
    public Queue reminderDelayQueue() {
        return QueueBuilder.durable(MqQueue.REMINDER_DELAY)
                .deadLetterExchange(MqExchange.TASKS)
                .deadLetterRoutingKey(MqKey.TASK_NOTIFY_FIRE)
                .build();
    }

    /** main → notify 能力结果回流队列（quorum + DLX，与业务队列参数约定一致） */
    @Bean
    public Queue resultNotifyCapabilityQueue() {
        return QueueBuilder.durable(MqQueue.RESULT_NOTIFY_CAPABILITY)
                .quorum()
                .deadLetterExchange(MqExchange.DLX)
                .build();
    }

    /** on_event 事件提醒队列（notify 独占，绑定公告完成事件——首个接入的事件源） */
    @Bean
    public Queue reminderEventQueue() {
        return QueueBuilder.durable(MqQueue.REMINDER_EVENT)
                .quorum()
                .deadLetterExchange(MqExchange.DLX)
                .build();
    }

    @Bean
    public Binding reminderFireBinding() {
        return BindingBuilder.bind(reminderFireQueue())
                .to(tasksExchange()).with(MqKey.TASK_NOTIFY_FIRE);
    }

    @Bean
    public Binding reminderDelayBinding() {
        return BindingBuilder.bind(reminderDelayQueue())
                .to(tasksExchange()).with(MqKey.REMINDER_DELAY);
    }

    @Bean
    public Binding resultNotifyCapabilityBinding() {
        return BindingBuilder.bind(resultNotifyCapabilityQueue())
                .to(resultsExchange()).with(MqKey.RESULT_NOTIFY_CAPABILITY);
    }

    /** 事件提醒绑定：公告完成事件（首个事件源；后续事件类型在此追加绑定） */
    @Bean
    public Binding reminderEventAnnouncementBinding() {
        return BindingBuilder.bind(reminderEventQueue())
                .to(resultsExchange()).with(MqKey.RESULT_ANNOUNCEMENT_DONE);
    }
}
