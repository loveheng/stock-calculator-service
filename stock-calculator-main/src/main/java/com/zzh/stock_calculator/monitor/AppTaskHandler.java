package com.zzh.stock_calculator.monitor;

/**
 * main 本地定时任务处理器（pull_task_config CALENDAR 行表驱动调度）：main 进程内的 cron 型任务
 * 统一实现本接口注册为 Bean，调度由 {@link CalendarTaskClaimScheduler} 按 schedule_mode=CALENDAR 行
 * 认领执行——cron/enabled/时区全部落库，改库即生效、免改配置免重部署。
 * <p>taskCode 恒用 job. 前缀：task. 前缀已被 MQ work routing key 占用（MqKey），
 * 本地任务码必须与之区分。</p>
 * <p>接口放 monitor 基包（开放 API）：announcement/crawler 任务实现它，依赖单向
 * 合法（先例：PullLoopDispatchPort 端口宿主模式）。</p>
 */
public interface AppTaskHandler {

    // ==================== taskCode 常量（与 data.sql 播种行一一对应） ====================

    /** 公告批处理发布（PENDING 扫描 → task.announcement.process，未终态每轮重发） */
    String TASK_ANNOUNCEMENT_PROCESS = "job.announcement.process";

    /** 订阅快照定时重推（兜底快照丢失，R6） */
    String TASK_ANNOUNCEMENT_SNAPSHOT = "job.announcement.snapshot";

    /** 公告向量存量对账回填（metadata kind/annDate 补齐，默认停用） */
    String TASK_SEARCH_BACKFILL = "job.search.backfill";

    /** CLS 存量对账自愈（扫缺补发 task.embedding.compute） */
    String TASK_EMBEDDING_BACKFILL = "job.embedding.backfill";

    /** 向量化统计报告（每 interval-days 一份，UTC 检查点） */
    String TASK_EMBEDDING_REPORT = "job.embedding.report";

    /** 数据管线巡检告警（队列积压/停机/PENDING 停滞，R4） */
    String TASK_PIPELINE_WATCH = "job.pipeline.watch";

    /** 任务码（pull_task_config.task_code，job. 前缀） */
    String taskCode();

    /** 认领成功后的执行体（原 @Scheduled 方法体；抛异常由调度器回滚游标重认领） */
    void run();
}
