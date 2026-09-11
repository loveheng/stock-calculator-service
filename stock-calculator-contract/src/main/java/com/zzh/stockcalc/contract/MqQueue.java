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
    public static final String TASK_ANNOUNCEMENT_PROCESS = "task.announcement.process.q";

    /** 向量化计算任务（worker 竞争消费，prefetch=8） */
    public static final String TASK_EMBEDDING_COMPUTE = "task.embedding.compute.q";

    /** 历史补录触发（collector 单发单收） */
    public static final String TASK_HISTORY_SYNC = "task.history.sync.q";

    /** 结果入库（主服务消费，绑定 result.#） */
    public static final String RESULT_INGEST = "result.ingest.q";

    /** 控制面（collector 消费，绑定 control.#：订阅快照等，覆盖式处理） */
    public static final String COLLECTOR_CONTROL = "collector.control.q";

    // ========== 伴生队列 ==========

    /** result.ingest.q 的 TTL 重试环（classic，quorum 不支持 per-queue TTL） */
    public static final String RESULT_INGEST_RETRY = "result.ingest.q.retry";

    public static final String TASK_ANNOUNCEMENT_PROCESS_RETRY = "task.announcement.process.q.retry";

    public static final String TASK_EMBEDDING_COMPUTE_RETRY = "task.embedding.compute.q.retry";

    public static final String TASK_HISTORY_SYNC_RETRY = "task.history.sync.q.retry";

    /** 死信停放队列（消费方 x-death >= 3 后主动投递，绑定 dead.#） */
    public static final String DEAD = "dead.q";
}
