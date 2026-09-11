package com.zzh.stockcalc.contract;

/**
 * MQ 交换机常量（设计文档 §4.1）：按「方向」划分的三个 topic 交换机 + 死信交换机。
 * 声明参数（durable、非 autoDelete、topic 类型）两侧必须一致，声明代码在各模块的拓扑配置类。
 */
public final class MqExchange {

    private MqExchange() {}

    /** 任务下行：主服务 → worker/collector */
    public static final String TASKS = "stockcalc.tasks";

    /** 结果上行：collector/worker → 主服务 */
    public static final String RESULTS = "stockcalc.results";

    /** 需求定义：主服务 → collector（订阅快照等） */
    public static final String CONTROL = "stockcalc.control";

    /** 死信交换机：各工作队列的 DLX；绑定原 routing key → 对应 retry 队列，dead.# → dead.q */
    public static final String DLX = "stockcalc.dlx";
}
