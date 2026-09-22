package com.zzh.stockcalc.contract;

/**
 * Routing key 常量（设计文档 §4.3 消息协议表）。
 * 消息 type 与 routing key 同名，排障时队列/日志/信封三方可对齐。
 */
public final class MqKey {

    private MqKey() {}

    // ========== task.*（主服务 → 数据服务） ==========

    public static final String TASK_ANNOUNCEMENT_PROCESS =
        "task.announcement.process";
    public static final String TASK_EMBEDDING_COMPUTE =
        "task.embedding.compute";
    public static final String TASK_HISTORY_SYNC = "task.history.sync";

    /** 自循环拉取任务（种子经 delay 队列 TTL 到期后以此 key 死信进工作队列） */
    public static final String TASK_CLS_PULL = "task.cls.pull";
    public static final String TASK_ANNOUNCEMENT_COLLECT =
        "task.announcement.collect";

    /** 自循环延迟队列绑定 key（种子发布目标；到期后 DLX 改写为对应 work key） */
    public static final String TASK_CLS_PULL_DELAY = "task.cls.pull.delay";
    public static final String TASK_ANNOUNCEMENT_COLLECT_DELAY =
        "task.announcement.collect.delay";

    /** 日历型定时任务（docs/architecture/pull-loop-unification.md §8，CALENDAR 模式：
     *  无 delay 队列，main 看门狗 CAS 认领后直发本 key 到 TASKS 交换机） */
    public static final String TASK_HELLO_WORLD = "task.hello.world";

    // ========== task.* copilot 记忆链（docs/copilot/memory-profile.md §三/§五） ==========

    /** copilot 记忆提炼种子（main 每轮聊天落库后发布，仅 userId+sessionId，per-message TTL） */
    public static final String TASK_MEMORY_EXTRACT_DELAY =
        "task.memory.extract.delay";
    /** copilot 记忆提炼任务（tick 闸门通过后差量下发，data MemoryExtractWorker） */
    public static final String TASK_MEMORY_EXTRACT = "task.memory.extract";
    /** copilot 画像重抽任务（变化驱动触发，data MemoryProfileWorker） */
    public static final String TASK_MEMORY_PROFILE = "task.memory.profile";

    // ========== task.* kg（docs/ai-pipeline/cls-news-kg.md §4） ==========

    /** 《新闻联播》要闻知识图谱抽取任务（CALENDAR 认领后 main 发布，data KgExtractWorker） */
    public static final String TASK_KG_EXTRACT = "task.kg.extract";

    /** 常态拉取配置快照（main → collector，覆盖式缓存）与续期心跳回报（data → main） */
    public static final String CONTROL_PULL_CONFIG = "control.pull.config";
    public static final String RESULT_PULL_HEARTBEAT = "result.pull.heartbeat";

    // ========== orchestration 异步任务终态事件（步 6-2 mq_wait 唤醒 + 步 6-3b SSE，memory 定案②） ==========
    // 下游完成/失败后发回；mq_wait 按 correlation_id 匹配唤醒，main 侧按它清映射推 SSE（GC 双保险触发源）

    /** 异步任务完成事件（routing key 后缀带业务任务类型，如 task.completed.announcement） */
    public static final String TASK_COMPLETED_PREFIX = "task.completed.";
    /** 异步任务失败事件 */
    public static final String TASK_FAILED_PREFIX = "task.failed.";

    /** orchestration 任务启动请求（dispatch/create_task 即刻返回后，消费侧取实例调 Executor 真异步） */
    public static final String TASK_ORCHESTRATION_RUN = "task.orchestration.run";

    // ========== result.*（数据服务 → 主服务） ==========

    /** CLS 电报：解析好的文章 + 字典 + 关联（阶段 1 链路） */
    public static final String RESULT_CLS_ARTICLE = "result.cls.article";
    /** CLS 历史补录执行报告 */
    public static final String RESULT_CLS_HISTORY_REPORT =
        "result.cls.history.report";
    /** 公告采集入库（PENDING） */
    public static final String RESULT_ANNOUNCEMENT_COLLECTED =
        "result.announcement.collected";
    /** 公告处理成功（content+summary+embedding 三件套） */
    public static final String RESULT_ANNOUNCEMENT_DONE =
        "result.announcement.done";
    /** 公告处理失败（错误分类回报，状态机在主服务计次） */
    public static final String RESULT_ANNOUNCEMENT_FAILED =
        "result.announcement.failed";
    /** 通用 webhook 摄取文章（§8 阶段 5：新源接入只改 data + contract） */
    public static final String RESULT_ARTICLE_INGESTED =
        "result.article.ingested";
    /** 向量化计算结果 */
    public static final String RESULT_EMBEDDING_DONE = "result.embedding.done";
    /** 知识图谱抽取成功（证据 payload 上行，main 落库融合） */
    public static final String RESULT_KG_DONE = "result.kg.done";
    /** 知识图谱抽取失败（错误三分类回报，状态机在主服务计次） */
    public static final String RESULT_KG_FAILED = "result.kg.failed";

    // ========== result.* copilot 记忆链 ==========

    /** copilot 记忆提炼 tick：delay 队列 TTL 到期经 DLX 改写（RESULTS 交换机）回 main——
     *  main 在途锁 CAS + 差量重算（空 drop）后才下发提炼任务，防风暴闸门（决策 #16/#20）。
     *  注意与拉取环不同：改写目标是结果交换机而非 TASKS（定时到期必须回到控制面 main） */
    public static final String RESULT_MEMORY_EXTRACT_TICK =
        "result.memory.extract.tick";
    /** copilot 记忆提炼结果（窗口记忆 upsert + 水位推进清锁） */
    public static final String RESULT_MEMORY_EXTRACTED =
        "result.memory.extracted";
    /** copilot 画像重抽结果（version+1 + 游标推进至快照 max(updated_at)） */
    public static final String RESULT_MEMORY_PROFILE = "result.memory.profile";

    // ========== notify 提醒链（docs/notify/design.md §4.2） ==========

    /** notify → main 能力请求（能力名+参数，traceId 进头） */
    public static final String TASK_NOTIFY_CAPABILITY = "task.notify.capability";
    /** main → notify 能力结果（摘要+结果，traceId 关联） */
    public static final String RESULT_NOTIFY_CAPABILITY =
        "result.notify.capability";
    /** notify → main 组装后的通知文本（main push 消费者转 SSE 触达） */
    public static final String NOTIFY_PUSH = "notify.push";
    /** 定时提醒种子发布目标（per-message TTL，到期 DLX 改写为 TASK_NOTIFY_FIRE） */
    public static final String REMINDER_DELAY = "reminder.delay";
    /** 种子到期后经 DLX 改写落 fire 队列的 key */
    public static final String TASK_NOTIFY_FIRE = "reminder.fire";

    // ========== control.*（主服务 → collector） ==========

    public static final String CONTROL_SUBSCRIPTION_SNAPSHOT =
        "control.subscription.snapshot";

    // ========== dead.*（消费方主动投递到死信停放） ==========

    public static final String DEAD_PREFIX = "dead.";

    // ========== 通配绑定 ==========

    /** result.ingest.q 与其 retry 队列在各自交换机上的绑定 */
    public static final String BIND_RESULT_ALL = "result.#";
    /** collector.control.q 绑定 */
    public static final String BIND_CONTROL_ALL = "control.#";
    /** dead.q 绑定 */
    public static final String BIND_DEAD_ALL = "dead.#";
}
