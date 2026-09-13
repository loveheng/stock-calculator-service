package com.zzh.stock_calculator.monitor;

import com.zzh.stockcalc.contract.message.PullConfigPayload;

/**
 * 自循环控制面发布端口（docs/pull-loop-unification-design.md L4）：monitor 看门狗
 * 对 MQ 的全部出向依赖收敛于此接口，实现挂在 crawler（PullLoopDispatchAdapter，
 * 委托 TaskPublisher）——保证依赖单向（crawler → monitor），避免
 * monitor → crawler 的 TaskDispatchApi 引用与心跳事件引用构成 Modulith 环。
 */
public interface PullLoopDispatchPort {

    /** 推送配置快照到 control.pull.config（覆盖式，订阅快照同款语义） */
    void pushConfig(PullConfigPayload payload);

    /** 补种：发布种子到对应延迟队列（per-message expiration，幂等由 data 深度守卫兜底） */
    void dispatchSeed(String delayKey, long ttlMs);

    /** 日历任务认领投递（§8，L8/L12）：直发 TASKS 交换机 routing key = taskKey，
     *  无 TTL、无 delay 队列、无信封（消费端不解析载荷）；投递资格由 CAS 认领保证 */
    void dispatchCalendarTask(String taskKey);
}
