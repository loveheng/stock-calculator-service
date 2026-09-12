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
