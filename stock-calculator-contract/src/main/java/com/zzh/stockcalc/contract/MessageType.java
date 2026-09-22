package com.zzh.stockcalc.contract;

/**
 * 消息类型常量：与 MqKey 的 routing key 同名（设计文档 §4.3），
 * 单独成类用于信封 type 字段的取值约束与消费端 switch 路由。
 */
public final class MessageType {

    private MessageType() {}

    public static final String RESULT_CLS_ARTICLE = MqKey.RESULT_CLS_ARTICLE;
    public static final String RESULT_CLS_HISTORY_REPORT =
        MqKey.RESULT_CLS_HISTORY_REPORT;
    public static final String RESULT_ANNOUNCEMENT_COLLECTED =
        MqKey.RESULT_ANNOUNCEMENT_COLLECTED;
    public static final String RESULT_ANNOUNCEMENT_DONE =
        MqKey.RESULT_ANNOUNCEMENT_DONE;
    public static final String RESULT_ANNOUNCEMENT_FAILED =
        MqKey.RESULT_ANNOUNCEMENT_FAILED;
    public static final String RESULT_ARTICLE_INGESTED =
        MqKey.RESULT_ARTICLE_INGESTED;
    public static final String RESULT_EMBEDDING_DONE =
        MqKey.RESULT_EMBEDDING_DONE;
    public static final String TASK_ANNOUNCEMENT_PROCESS =
        MqKey.TASK_ANNOUNCEMENT_PROCESS;
    public static final String TASK_EMBEDDING_COMPUTE =
        MqKey.TASK_EMBEDDING_COMPUTE;
    public static final String TASK_HISTORY_SYNC = MqKey.TASK_HISTORY_SYNC;
    public static final String TASK_CLS_PULL = MqKey.TASK_CLS_PULL;
    public static final String TASK_ANNOUNCEMENT_COLLECT =
        MqKey.TASK_ANNOUNCEMENT_COLLECT;
    public static final String CONTROL_PULL_CONFIG = MqKey.CONTROL_PULL_CONFIG;
    public static final String RESULT_PULL_HEARTBEAT =
        MqKey.RESULT_PULL_HEARTBEAT;
    public static final String CONTROL_SUBSCRIPTION_SNAPSHOT =
        MqKey.CONTROL_SUBSCRIPTION_SNAPSHOT;

    // ========== copilot 记忆链（docs/copilot/memory-profile.md） ==========

    public static final String TASK_MEMORY_EXTRACT_DELAY =
        MqKey.TASK_MEMORY_EXTRACT_DELAY;
    public static final String TASK_MEMORY_EXTRACT = MqKey.TASK_MEMORY_EXTRACT;
    public static final String TASK_MEMORY_PROFILE = MqKey.TASK_MEMORY_PROFILE;
    public static final String RESULT_MEMORY_EXTRACT_TICK =
        MqKey.RESULT_MEMORY_EXTRACT_TICK;
    public static final String RESULT_MEMORY_EXTRACTED =
        MqKey.RESULT_MEMORY_EXTRACTED;
    public static final String RESULT_MEMORY_PROFILE =
        MqKey.RESULT_MEMORY_PROFILE;

    // ========== kg（docs/ai-pipeline/cls-news-kg.md §4） ==========

    public static final String TASK_KG_EXTRACT = MqKey.TASK_KG_EXTRACT;
    public static final String RESULT_KG_DONE = MqKey.RESULT_KG_DONE;
    public static final String RESULT_KG_FAILED = MqKey.RESULT_KG_FAILED;

    // ========== notify 提醒链（docs/notify/design.md §4.2） ==========

    public static final String TASK_NOTIFY_CAPABILITY =
        MqKey.TASK_NOTIFY_CAPABILITY;
    public static final String RESULT_NOTIFY_CAPABILITY =
        MqKey.RESULT_NOTIFY_CAPABILITY;
    public static final String NOTIFY_PUSH = MqKey.NOTIFY_PUSH;
    public static final String TASK_NOTIFY_FIRE = MqKey.TASK_NOTIFY_FIRE;
}
