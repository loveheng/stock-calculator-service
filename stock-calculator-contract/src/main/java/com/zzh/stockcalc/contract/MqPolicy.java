package com.zzh.stockcalc.contract;

/**
 * MQ 策略常量（设计文档 §4.1/§4.2/§4.4）：重试环参数与生产方标识。
 * 队列声明参数（TTL）与消费端死信判定（投递次数）两侧必须取同一常量。
 */
public final class MqPolicy {

    private MqPolicy() {}

    /** retry 队列的 x-message-ttl（毫秒）：到期经原交换机回原队列 */
    public static final int RETRY_TTL_MS = 30_000;

    /** 消费端重试环最大进入次数（x-death 累计计数）：达到即投 dead.q 停放 */
    public static final int MAX_DELIVERY_ATTEMPTS = 3;

    // ========== 生产方标识（信封 producer 字段） ==========

    public static final String PRODUCER_COLLECTOR = "datasvc-collector";
    public static final String PRODUCER_WORKER = "datasvc-worker";
    public static final String PRODUCER_MAIN = "stockcalc-main";
}
