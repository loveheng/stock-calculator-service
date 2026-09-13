package com.zzh.stock_calculator.monitor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 自循环续期心跳事件（docs/pull-loop-unification-design.md L4）：crawler 消费端收到
 * result.pull.heartbeat 后经本事件跨域移交 monitor 落表。事件对象置于 monitor 基包，
 * crawler 仅引用基包（Modulith 红线）。观测信号非控制信号（设计不变量 3）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PullHeartbeatEvent {

    /** 拉取源标识（task.cls.pull / task.announcement.collect） */
    private String taskCode;

    /** 续种时延迟队列深度（0 = 正常续种；>0 = 守卫跳过续种） */
    private int depth;

    /** 本轮实际应用的种子 TTL（毫秒；跳过续种时为 0） */
    private long appliedTtlMs;

    /** 续期时刻（epoch millis） */
    private long renewedAt;
}
