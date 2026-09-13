package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * control.pull.config 的 payload（docs/pull-loop-unification-design.md §3）：
 * main → collector 的常态拉取配置快照，覆盖式语义（订阅快照同款）——collector 以
 * 收到的 tasks 全量替换本地缓存。data 侧代码内置默认值（enabled=true、TTL=8min|1h），
 * 本快照只做覆盖；丢失由 main 看门狗周期性重推兜底。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PullConfigPayload {

    /** 配置版本（epoch millis，单调），collector 拒绝不大于本地缓存值的快照 */
    private long version;

    /** 拉取源配置全集（taskCode = MqKey.TASK_CLS_PULL / TASK_ANNOUNCEMENT_COLLECT） */
    private List<TaskConfig> tasks;

    /**
     * 单个拉取源配置。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskConfig {

        /** 拉取源标识（task. 前缀 routing key 名） */
        private String taskCode;

        /** 续期开关（false = 跳过续种，循环死亡；看门狗同时停补种，重启延迟=看门狗周期，L6） */
        private boolean enabled;

        /** 下一轮种子 TTL（毫秒，per-message expiration；LavinMQ per-message TTL 已实证） */
        private long ttlMs;
    }
}
