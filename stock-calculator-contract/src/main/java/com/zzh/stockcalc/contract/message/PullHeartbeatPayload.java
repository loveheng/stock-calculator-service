package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * result.pull.heartbeat 的 payload（docs/pull-loop-unification-design.md §3）：
 * data → main 的自循环续期回报，每轮一条（8min/1h 量级，可忽略）。
 * main 写 pull_heartbeat 表供看门狗判活与仪表盘展示；回报失败不影响循环
 * ——心跳是观测信号非控制信号（设计不变量 3），控制信号只有种子与深度守卫。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PullHeartbeatPayload {

    /** 拉取源标识（task.cls.pull / task.announcement.collect） */
    private String taskCode;

    /** 续种时延迟队列深度（0 = 正常续种；>0 = 守卫跳过续种，本轮仅报心跳） */
    private int depth;

    /** 本轮实际应用的种子 TTL（毫秒；depth>0 跳过续种时为 0） */
    private long appliedTtlMs;

    /** 续期时刻（epoch millis，main 落表口径） */
    private long renewedAt;
}
