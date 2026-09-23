package com.zzh.stock_calculator.config;

import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqPolicy;
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
 * 主服务侧 MQ 拓扑（设计文档 §4.1）：仅声明自己消费的 results 半区。
 * 队列参数必须与数据服务 MqTopologyConfig 严格一致（重复声明为幂等 no-op，
 * 参数漂移会触发 PRECONDITION_FAILED，改动需两侧同步）。
 * 无条件装配（MQ 单路径终态）。
 */
@Configuration
public class RabbitTopologyConfig {

    /**
     * 受门控的 AmqpAdmin（yml 已置 spring.rabbitmq.dynamic=false 关闭 Boot 自动装配）：
     * 仅 MQ 启用时存在，负责启动期声明下方拓扑；禁用时整个应用零 MQ 连接。
     */
    @Bean
    public AmqpAdmin amqpAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    public TopicExchange resultsExchange() {
        return new TopicExchange(MqExchange.RESULTS);
    }

    /** 业务领域事件交换机（P3 领域事件化）：main 发布 event.* 事实，编排器 fan-in 消费 */
    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange(MqExchange.EVENTS);
    }

    @Bean
    public TopicExchange dlxExchange() {
        return new TopicExchange(MqExchange.DLX);
    }

    /** 结果入库队列：quorum + DLX（与数据服务侧 businessQueue 参数一致） */
    @Bean
    public Queue resultIngestQueue() {
        return QueueBuilder.durable(MqQueue.RESULT_INGEST)
                .quorum()
                .deadLetterExchange(MqExchange.DLX)
                .build();
    }

    /** TTL 重试环（classic，quorum 不支持 per-queue TTL）：到期回 results 交换机原 key */
    @Bean
    public Queue resultIngestRetryQueue() {
        return QueueBuilder.durable(MqQueue.RESULT_INGEST_RETRY)
                .ttl(MqPolicy.RETRY_TTL_MS)
                .deadLetterExchange(MqExchange.RESULTS)
                .build();
    }

    /** 死信停放队列（classic） */
    @Bean
    public Queue deadQueue() {
        return QueueBuilder.durable(MqQueue.DEAD).build();
    }

    /** notify → main 推送队列（docs/notify/design.md §4.2 N5：触达出口收敛一处；
     *  quorum + DLX，与 notify 侧 NotifyMqTopologyConfig 参数一致） */
    @Bean
    public Queue notifyPushQueue() {
        return QueueBuilder.durable(MqQueue.NOTIFY_PUSH)
                .quorum()
                .deadLetterExchange(MqExchange.DLX)
                .build();
    }

    @Bean
    public Binding notifyPushBinding() {
        return BindingBuilder.bind(notifyPushQueue())
                .to(resultsExchange()).with(MqKey.NOTIFY_PUSH);
    }

    /** notify → main 能力请求队列（docs/notify/design.md §4.2：main 消费执行后沿
     *  result.notify.capability 回流；quorum + DLX，与 notify 侧参数一致） */
    @Bean
    public Queue taskNotifyCapabilityQueue() {
        return QueueBuilder.durable(MqQueue.TASK_NOTIFY_CAPABILITY)
                .quorum()
                .deadLetterExchange(MqExchange.DLX)
                .build();
    }

    @Bean
    public Binding taskNotifyCapabilityBinding() {
        // main 不声明 TASKS 交换机（下行半区属 data/notify 侧），绑定用内联幂等声明
        return BindingBuilder.bind(taskNotifyCapabilityQueue())
                .to(new TopicExchange(MqExchange.TASKS)).with(MqKey.TASK_NOTIFY_CAPABILITY);
    }

    @Bean
    public Binding resultIngestBinding() {
        return BindingBuilder.bind(resultIngestQueue()).to(resultsExchange()).with(MqKey.BIND_RESULT_ALL);
    }

    /** DLX 上 result.# → retry 队列（死信消息保留原 routing key） */
    @Bean
    public Binding dlxResultRetryBinding() {
        return BindingBuilder.bind(new Queue(MqQueue.RESULT_INGEST_RETRY))
                .to(dlxExchange()).with(MqKey.BIND_RESULT_ALL);
    }

    @Bean
    public Binding dlxDeadBinding() {
        return BindingBuilder.bind(new Queue(MqQueue.DEAD))
                .to(dlxExchange()).with(MqKey.BIND_DEAD_ALL);
    }
}
