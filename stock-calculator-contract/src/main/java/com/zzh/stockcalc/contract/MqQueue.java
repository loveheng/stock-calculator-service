package com.zzh.stockcalc.contract;

/**
 * MQ 队列常量（设计文档 §4.1）。
 * 队列参数约定：业务队列 quorum + DLX 指向 stockcalc.dlx；retry 队列 classic +
 * x-message-ttl=30s + DLX 指回原交换机；dead.q classic 停放。两侧声明参数必须一致。
 */
public final class MqQueue {

    private MqQueue() {}

    // ========== 业务队列 ==========

    /** 公告处理任务（worker 竞争消费，prefetch=2） */
    public static final String TASK_ANNOUNCEMENT_PROCESS =
        "task.announcement.process.q";

    /** 向量化计算任务（worker 竞争消费，prefetch=8） */
    public static final String TASK_EMBEDDING_COMPUTE =
        "task.embedding.compute.q";

    /** 历史补录触发（collector 单发单收） */
    public static final String TASK_HISTORY_SYNC = "task.history.sync.q";

    /** CLS 电报常态拉取任务（自循环工作队列，collector 门控消费，恒 ack 无 retry 环） */
    public static final String TASK_CLS_PULL = "task.cls.pull.q";

    /** CLS 拉取自循环延迟队列（classic、无消费者、无 x-message-ttl——TTL 逐条消息自带，
     *  DLX=TASKS 交换机 + DLK=task.cls.pull；见 docs/architecture/pull-loop-unification.md） */
    public static final String TASK_CLS_PULL_DELAY = "task.cls.pull.delay.q";

    /** 公告常态采集任务（自循环工作队列，collector 门控消费，恒 ack 无 retry 环） */
    public static final String TASK_ANNOUNCEMENT_COLLECT =
        "task.announcement.collect.q";

    /** 公告采集自循环延迟队列（语义同 TASK_CLS_PULL_DELAY，DLK=task.announcement.collect） */
    public static final String TASK_ANNOUNCEMENT_COLLECT_DELAY =
        "task.announcement.collect.delay.q";

    /** copilot 记忆提炼任务（worker 竞争消费） */
    public static final String TASK_MEMORY_EXTRACT = "task.memory.extract.q";

    /** copilot 画像重抽任务（worker 竞争消费） */
    public static final String TASK_MEMORY_PROFILE = "task.memory.profile.q";

    /** copilot 记忆提炼自延迟队列（classic、无消费者、TTL 逐条消息自带；到期 DLX 改写
     *  到 RESULTS 交换机 + result.memory.extract.tick 回 main——与拉取环不同，改写目标
     *  是结果交换机而非 TASKS，定时到期必须回到控制面；见 docs/copilot/memory-profile.md §五） */
    public static final String TASK_MEMORY_EXTRACT_DELAY =
        "task.memory.extract.delay.q";

    /** 日历型定时任务工作队列（§8 一次性消费：无 delay 队列、无续种，collector 门控消费） */
    public static final String TASK_HELLO_WORLD = "task.hello.world.q";

    /** 《新闻联播》要闻 KG 抽取任务（worker 竞争消费，prefetch=2） */
    public static final String TASK_KG_EXTRACT = "task.kg.extract.q";

    /** 结果入库（主服务消费，绑定 result.#） */
    public static final String RESULT_INGEST = "result.ingest.q";

    /** 控制面（collector 消费，绑定 control.#：订阅快照等，覆盖式处理） */
    public static final String COLLECTOR_CONTROL = "collector.control.q";

    // ========== notify 提醒链（docs/notify/design.md §4.2） ==========

    /** notify → main 能力请求（main 消费，内部调业务接口或 stock-mcp 经纪人） */
    public static final String TASK_NOTIFY_CAPABILITY = "task.notify.capability.q";

    /** main → notify 能力结果回流（含 traceId 关联） */
    public static final String RESULT_NOTIFY_CAPABILITY =
        "result.notify.capability.q";

    /** notify → main 推送（main 消费转 SSE/Web Push 触达） */
    public static final String NOTIFY_PUSH = "notify.push.q";

    /** 定时提醒种子队列（classic、无消费者、per-message TTL、DLX→TASKS 交换机；自循环钟摆） */
    public static final String REMINDER_DELAY = "reminder.delay.q";

    /** TTL 到期死信落地队列（notify 消费触发提醒） */
    public static final String REMINDER_FIRE = "reminder.fire.q";

    /** on_event 事件提醒队列（notify 独占消费：绑定具体事件 key 如 result.announcement.done，
     *  命中 trigger_spec.filter 即触发；docs/notify/design.md §五） */
    public static final String REMINDER_EVENT = "reminder.event.q";

    // ========== 伴生队列 ==========

    /** result.ingest.q 的 TTL 重试环（classic，quorum 不支持 per-queue TTL） */
    public static final String RESULT_INGEST_RETRY = "result.ingest.q.retry";

    public static final String TASK_ANNOUNCEMENT_PROCESS_RETRY =
        "task.announcement.process.q.retry";

    public static final String TASK_EMBEDDING_COMPUTE_RETRY =
        "task.embedding.compute.q.retry";

    public static final String TASK_HISTORY_SYNC_RETRY =
        "task.history.sync.q.retry";

    public static final String TASK_KG_EXTRACT_RETRY =
        "task.kg.extract.q.retry";

    /** 死信停放队列（消费方 x-death >= 3 后主动投递，绑定 dead.#） */
    public static final String DEAD = "dead.q";
}
