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

    /**
     * 历史补录队列：单活跃消费者（x-single-active-consumer）——单镜像多副本下
     * 「恰一个补录执行者」从打包门控迁移到协议仲裁：全舰队任一时刻仅一个副本
     * 消费（prefetch=1 串行多任务），活跃副本挂掉其余副本自动顶替；
     * CLS 动态频控在单消费者语义下与副本数天然解耦。
     */
    @Bean
    public Queue taskHistorySyncQueue() {
        return QueueBuilder.durable(MqQueue.TASK_HISTORY_SYNC)
            .quorum()
            .deadLetterExchange(MqExchange.DLX)
            .singleActiveConsumer()
            .build();
    }

    // ---- 自循环拉取队列（docs/architecture/pull-loop-unification.md §3） ----

    /** CLS 电报常态拉取工作队列：quorum、无 DLX——消费恒 ack（失败走下一轮续种），retry 环不适用 */
    @Bean
    public Queue taskClsPullQueue() {
        return pullWorkQueue(MqQueue.TASK_CLS_PULL);
    }

    /** CLS 拉取延迟队列：classic、无消费者；TTL 逐条消息自带（per-message expiration，
     *  LavinMQ 已实证），到期经 TASKS 交换机以 work key 死信进工作队列 */
    @Bean
    public Queue taskClsPullDelayQueue() {
        return pullDelayQueue(MqQueue.TASK_CLS_PULL_DELAY, MqKey.TASK_CLS_PULL);
    }

    /** 公告常态采集工作队列（语义同 CLS 拉取工作队列） */
    @Bean
    public Queue taskAnnouncementCollectQueue() {
        return pullWorkQueue(MqQueue.TASK_ANNOUNCEMENT_COLLECT);
    }

    /** 公告采集延迟队列（语义同 CLS 拉取延迟队列） */
    @Bean
    public Queue taskAnnouncementCollectDelayQueue() {
        return pullDelayQueue(
            MqQueue.TASK_ANNOUNCEMENT_COLLECT_DELAY,
            MqKey.TASK_ANNOUNCEMENT_COLLECT
        );
    }

    /** 日历型定时任务工作队列（§8 一次性消费：quorum、无 DLX、无 delay 队列——
     *  "钟"在 main 侧调度游标，看门狗 CAS 认领后直发，无种子无续种） */
    @Bean
    public Queue taskHelloWorldQueue() {
        return pullWorkQueue(MqQueue.TASK_HELLO_WORLD);
    }

    // ---- copilot 记忆链（docs/copilot/memory-profile.md §五） ----

    /** copilot 记忆提炼任务队列（worker 竞争消费；失败无重试环——水位不动 +
     *  在途锁超时由下个 tick 重发差量自愈，决策 #5） */
    @Bean
    public Queue taskMemoryExtractQueue() {
        return businessQueue(MqQueue.TASK_MEMORY_EXTRACT);
    }

    /** copilot 画像重抽任务队列（worker 竞争消费；变化驱动触发，无变化零调用） */
    @Bean
    public Queue taskMemoryProfileQueue() {
        return businessQueue(MqQueue.TASK_MEMORY_PROFILE);
    }

    /** copilot 记忆种子延迟队列：classic、无消费者、TTL 逐条消息自带（同款 per-message
     *  expiration 已在拉取环 LavinMQ 实证）；到期经 RESULTS 交换机以 tick key 死信回 main——
     *  与拉取环不同：改写目标是结果交换机而非 TASKS，定时到期必须回控制面（种子信封 type
     *  也按 tick 写，保证消费端 type == 到期后的 routing key） */
    @Bean
    public Queue taskMemoryExtractDelayQueue() {
        return QueueBuilder.durable(MqQueue.TASK_MEMORY_EXTRACT_DELAY)
            .deadLetterExchange(MqExchange.RESULTS)
            .deadLetterRoutingKey(MqKey.RESULT_MEMORY_EXTRACT_TICK)
            .build();
    }

    @Bean
    public Queue resultIngestQueue() {
        return businessQueue(MqQueue.RESULT_INGEST);
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

    /** 自循环工作队列：quorum、无 DLX（消费恒 ack，失败语义 = 下一轮续种照常） */
    private static Queue pullWorkQueue(String name) {
        return QueueBuilder.durable(name).quorum().build();
    }

    /** 自循环延迟队列：TTL 逐条消息自带——per-queue x-message-ttl 声明期不可变，
     *  动态调速（设计 L5）必须逐条携带 expiration */
    private static Queue pullDelayQueue(String name, String workKey) {
        return QueueBuilder.durable(name)
            .deadLetterExchange(MqExchange.TASKS)
            .deadLetterRoutingKey(workKey)
            .build();
    }

    // ==================== retry 伴生队列（classic + TTL + DLX 回原交换机） ====================

    @Bean
    public Queue resultIngestRetryQueue() {
        return retryQueue(MqQueue.RESULT_INGEST_RETRY, MqExchange.RESULTS);
    }

    @Bean
    public Queue taskAnnouncementProcessRetryQueue() {
        return retryQueue(
            MqQueue.TASK_ANNOUNCEMENT_PROCESS_RETRY,
            MqExchange.TASKS
        );
    }

    @Bean
    public Queue taskEmbeddingComputeRetryQueue() {
        return retryQueue(
            MqQueue.TASK_EMBEDDING_COMPUTE_RETRY,
            MqExchange.TASKS
        );
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
        return bind(
            MqQueue.TASK_ANNOUNCEMENT_PROCESS,
            tasksExchange(),
            MqKey.TASK_ANNOUNCEMENT_PROCESS
        );
    }

    @Bean
    public Binding taskEmbeddingComputeBinding() {
        return bind(
            MqQueue.TASK_EMBEDDING_COMPUTE,
            tasksExchange(),
            MqKey.TASK_EMBEDDING_COMPUTE
        );
    }

    @Bean
    public Binding taskHistorySyncBinding() {
        return bind(
            MqQueue.TASK_HISTORY_SYNC,
            tasksExchange(),
            MqKey.TASK_HISTORY_SYNC
        );
    }

    // ---- tasks：自循环拉取（种子发 delay key，TTL 到期 DLX 改写为 work key） ----

    @Bean
    public Binding taskClsPullBinding() {
        return bind(
            MqQueue.TASK_CLS_PULL,
            tasksExchange(),
            MqKey.TASK_CLS_PULL
        );
    }

    @Bean
    public Binding taskClsPullDelayBinding() {
        return bind(
            MqQueue.TASK_CLS_PULL_DELAY,
            tasksExchange(),
            MqKey.TASK_CLS_PULL_DELAY
        );
    }

    @Bean
    public Binding taskAnnouncementCollectBinding() {
        return bind(
            MqQueue.TASK_ANNOUNCEMENT_COLLECT,
            tasksExchange(),
            MqKey.TASK_ANNOUNCEMENT_COLLECT
        );
    }

    @Bean
    public Binding taskAnnouncementCollectDelayBinding() {
        return bind(
            MqQueue.TASK_ANNOUNCEMENT_COLLECT_DELAY,
            tasksExchange(),
            MqKey.TASK_ANNOUNCEMENT_COLLECT_DELAY
        );
    }

    // ---- tasks：日历型定时任务（§8 看门狗直发 work key，无 delay 环节） ----

    @Bean
    public Binding taskHelloWorldBinding() {
        return bind(
            MqQueue.TASK_HELLO_WORLD,
            tasksExchange(),
            MqKey.TASK_HELLO_WORLD
        );
    }

    // ---- tasks：copilot 记忆链（种子发 delay key，TTL 到期 DLX 改写为 tick key 回 main） ----

    @Bean
    public Binding taskMemoryExtractBinding() {
        return bind(
            MqQueue.TASK_MEMORY_EXTRACT,
            tasksExchange(),
            MqKey.TASK_MEMORY_EXTRACT
        );
    }

    @Bean
    public Binding taskMemoryProfileBinding() {
        return bind(
            MqQueue.TASK_MEMORY_PROFILE,
            tasksExchange(),
            MqKey.TASK_MEMORY_PROFILE
        );
    }

    @Bean
    public Binding taskMemoryExtractDelayBinding() {
        return bind(
            MqQueue.TASK_MEMORY_EXTRACT_DELAY,
            tasksExchange(),
            MqKey.TASK_MEMORY_EXTRACT_DELAY
        );
    }

    // ---- results：结果入库 ----

    @Bean
    public Binding resultIngestBinding() {
        return bind(
            MqQueue.RESULT_INGEST,
            resultsExchange(),
            MqKey.BIND_RESULT_ALL
        );
    }

    // ---- control：订阅快照（控制面队列/绑定由 SubscriptionSnapshotConsumer 的
    //      @QueueBinding 声明式自建——每副本匿名队列，native 下不可用 SpEL 引队列 bean） ----

    // ---- dlx：原 routing key → 对应 retry 队列；dead.# → 停放 ----

    @Bean
    public Binding dlxTaskAnnouncementRetryBinding() {
        return bind(
            MqQueue.TASK_ANNOUNCEMENT_PROCESS_RETRY,
            dlxExchange(),
            MqKey.TASK_ANNOUNCEMENT_PROCESS
        );
    }

    @Bean
    public Binding dlxTaskEmbeddingRetryBinding() {
        return bind(
            MqQueue.TASK_EMBEDDING_COMPUTE_RETRY,
            dlxExchange(),
            MqKey.TASK_EMBEDDING_COMPUTE
        );
    }

    @Bean
    public Binding dlxTaskHistoryRetryBinding() {
        return bind(
            MqQueue.TASK_HISTORY_SYNC_RETRY,
            dlxExchange(),
            MqKey.TASK_HISTORY_SYNC
        );
    }

    @Bean
    public Binding dlxResultRetryBinding() {
        return bind(
            MqQueue.RESULT_INGEST_RETRY,
            dlxExchange(),
            MqKey.BIND_RESULT_ALL
        );
    }

    @Bean
    public Binding dlxDeadBinding() {
        return bind(MqQueue.DEAD, dlxExchange(), MqKey.BIND_DEAD_ALL);
    }

    private static Binding bind(
        String queueName,
        TopicExchange exchange,
        String routingKey
    ) {
        return BindingBuilder.bind(new Queue(queueName))
            .to(exchange)
            .with(routingKey);
    }
}
