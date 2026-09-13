package com.zzh.stock_calculator.data.config;

import com.zzh.stockcalc.contract.ContractRuntimeHints;
import com.zzh.stockcalc.contract.MqExchange;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqPolicy;
import com.zzh.stockcalc.contract.MqQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * MQ 拓扑声明（设计文档 §4.1，D4）：数据服务侧声明全量交换机/队列/绑定。
 * 主服务侧仅声明自己消费的 results 半区（参数与本类严格一致，重复声明为幂等 no-op）。
 * 参数约定：业务队列 quorum + DLX；retry 队列 classic + per-queue TTL（quorum 不支持）；
 * dead.q classic 停放。
 * 本类同时携带 ContractRuntimeHints（native 消费信封的反射注册，无条件）：
 * 手工 readValue/convertValue 不被 AOT 推断，缺注册则 native 反序列化必挂（R1 实证）；
 * 并注册心跳 watchdog 配置（MqHeartbeatWatchdog，无条件组件）。
 */
@Configuration
@ImportRuntimeHints(ContractRuntimeHints.class)
@EnableConfigurationProperties(HeartbeatProperties.class)
public class MqTopologyConfig {

    // ==================== 交换机 ====================

    @Bean
    public TopicExchange tasksExchange() {
        return new TopicExchange(MqExchange.TASKS);
    }

    @Bean
    public TopicExchange resultsExchange() {
        return new TopicExchange(MqExchange.RESULTS);
    }

    @Bean
    public TopicExchange controlExchange() {
        return new TopicExchange(MqExchange.CONTROL);
    }

    @Bean
    public TopicExchange dlxExchange() {
        return new TopicExchange(MqExchange.DLX);
    }

    // ==================== 业务队列（quorum + DLX） ====================

    @Bean
    public Queue taskAnnouncementProcessQueue() {
        return businessQueue(MqQueue.TASK_ANNOUNCEMENT_PROCESS);
    }

    @Bean
    public Queue taskEmbeddingComputeQueue() {
        return businessQueue(MqQueue.TASK_EMBEDDING_COMPUTE);
    }

    @Bean
    public Queue taskHistorySyncQueue() {
        return businessQueue(MqQueue.TASK_HISTORY_SYNC);
    }

    @Bean
    public Queue resultIngestQueue() {
        return businessQueue(MqQueue.RESULT_INGEST);
    }

    /** 控制面队列：快照覆盖式语义，消息丢失/失败由下一次快照兜底，不进重试环 */
    @Bean
    public Queue collectorControlQueue() {
        return QueueBuilder.durable(MqQueue.COLLECTOR_CONTROL).build();
    }

    /** 死信停放队列：classic，人工/告警处置 */
    @Bean
    public Queue deadQueue() {
        return QueueBuilder.durable(MqQueue.DEAD).build();
    }

    private static Queue businessQueue(String name) {
        return QueueBuilder.durable(name)
                .quorum()
                .deadLetterExchange(MqExchange.DLX)
                .build();
    }

    // ==================== retry 伴生队列（classic + TTL + DLX 回原交换机） ====================

    @Bean
    public Queue resultIngestRetryQueue() {
        return retryQueue(MqQueue.RESULT_INGEST_RETRY, MqExchange.RESULTS);
    }

    @Bean
    public Queue taskAnnouncementProcessRetryQueue() {
        return retryQueue(MqQueue.TASK_ANNOUNCEMENT_PROCESS_RETRY, MqExchange.TASKS);
    }

    @Bean
    public Queue taskEmbeddingComputeRetryQueue() {
        return retryQueue(MqQueue.TASK_EMBEDDING_COMPUTE_RETRY, MqExchange.TASKS);
    }

    @Bean
    public Queue taskHistorySyncRetryQueue() {
        return retryQueue(MqQueue.TASK_HISTORY_SYNC_RETRY, MqExchange.TASKS);
    }

    private static Queue retryQueue(String name, String originExchange) {
        return QueueBuilder.durable(name)
                .ttl(MqPolicy.RETRY_TTL_MS)
                .deadLetterExchange(originExchange)
                .build();
    }

    // ==================== 绑定 ====================

    // ---- tasks：任务队列 ----

    @Bean
    public Binding taskAnnouncementProcessBinding() {
        return bind(MqQueue.TASK_ANNOUNCEMENT_PROCESS, tasksExchange(), MqKey.TASK_ANNOUNCEMENT_PROCESS);
    }

    @Bean
    public Binding taskEmbeddingComputeBinding() {
        return bind(MqQueue.TASK_EMBEDDING_COMPUTE, tasksExchange(), MqKey.TASK_EMBEDDING_COMPUTE);
    }

    @Bean
    public Binding taskHistorySyncBinding() {
        return bind(MqQueue.TASK_HISTORY_SYNC, tasksExchange(), MqKey.TASK_HISTORY_SYNC);
    }

    // ---- results：结果入库 ----

    @Bean
    public Binding resultIngestBinding() {
        return bind(MqQueue.RESULT_INGEST, resultsExchange(), MqKey.BIND_RESULT_ALL);
    }

    // ---- control：订阅快照 ----

    @Bean
    public Binding collectorControlBinding() {
        return bind(MqQueue.COLLECTOR_CONTROL, controlExchange(), MqKey.BIND_CONTROL_ALL);
    }

    // ---- dlx：原 routing key → 对应 retry 队列；dead.# → 停放 ----

    @Bean
    public Binding dlxTaskAnnouncementRetryBinding() {
        return bind(MqQueue.TASK_ANNOUNCEMENT_PROCESS_RETRY, dlxExchange(), MqKey.TASK_ANNOUNCEMENT_PROCESS);
    }

    @Bean
    public Binding dlxTaskEmbeddingRetryBinding() {
        return bind(MqQueue.TASK_EMBEDDING_COMPUTE_RETRY, dlxExchange(), MqKey.TASK_EMBEDDING_COMPUTE);
    }

    @Bean
    public Binding dlxTaskHistoryRetryBinding() {
        return bind(MqQueue.TASK_HISTORY_SYNC_RETRY, dlxExchange(), MqKey.TASK_HISTORY_SYNC);
    }

    @Bean
    public Binding dlxResultRetryBinding() {
        return bind(MqQueue.RESULT_INGEST_RETRY, dlxExchange(), MqKey.BIND_RESULT_ALL);
    }

    @Bean
    public Binding dlxDeadBinding() {
        return bind(MqQueue.DEAD, dlxExchange(), MqKey.BIND_DEAD_ALL);
    }

    private static Binding bind(String queueName, TopicExchange exchange, String routingKey) {
        return BindingBuilder.bind(new Queue(queueName)).to(exchange).with(routingKey);
    }
}
