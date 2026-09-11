package com.zzh.stockcalc.contract;

/**
 * Routing key 常量（设计文档 §4.3 消息协议表）。
 * 消息 type 与 routing key 同名，排障时队列/日志/信封三方可对齐。
 */
public final class MqKey {

    private MqKey() {}

    // ========== task.*（主服务 → 数据服务） ==========

    public static final String TASK_ANNOUNCEMENT_PROCESS = "task.announcement.process";
    public static final String TASK_EMBEDDING_COMPUTE = "task.embedding.compute";
    public static final String TASK_HISTORY_SYNC = "task.history.sync";

    // ========== result.*（数据服务 → 主服务） ==========

    /** CLS 电报：解析好的文章 + 字典 + 关联（阶段 1 链路） */
    public static final String RESULT_CLS_ARTICLE = "result.cls.article";
    /** CLS 历史补录执行报告 */
    public static final String RESULT_CLS_HISTORY_REPORT = "result.cls.history.report";
    /** 公告采集入库（PENDING） */
    public static final String RESULT_ANNOUNCEMENT_COLLECTED = "result.announcement.collected";
    /** 公告处理成功（content+summary+embedding 三件套） */
    public static final String RESULT_ANNOUNCEMENT_DONE = "result.announcement.done";
    /** 公告处理失败（错误分类回报，状态机在主服务计次） */
    public static final String RESULT_ANNOUNCEMENT_FAILED = "result.announcement.failed";
    /** 通用 webhook 摄取文章（§8 阶段 5：新源接入只改 data + contract） */
    public static final String RESULT_ARTICLE_INGESTED = "result.article.ingested";
    /** 向量化计算结果 */
    public static final String RESULT_EMBEDDING_DONE = "result.embedding.done";

    // ========== control.*（主服务 → collector） ==========

    public static final String CONTROL_SUBSCRIPTION_SNAPSHOT = "control.subscription.snapshot";

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
