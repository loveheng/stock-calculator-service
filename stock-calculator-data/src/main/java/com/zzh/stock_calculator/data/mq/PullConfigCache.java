package com.zzh.stock_calculator.data.mq;

import com.zzh.stockcalc.contract.message.PullConfigPayload;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自循环配置本地缓存（docs/pull-loop-unification-design.md §3）：collector 无 DB（D2），
 * 拉取源配置以「version + overrides」内存态持有，由 control.pull.config 覆盖式更新，
 * 订阅快照同款语义。version 单调（epoch millis）拒绝旧快照回灌。
 * resolve 以 control 覆盖优先、代码内置默认兜底（配置缺发/丢失不死锁循环）。
 */
@Component
@ConditionalOnProperty(prefix = "datasvc.collector", name = "enabled", havingValue = "true")
public class PullConfigCache {

    private volatile Map<String, PullConfigPayload.TaskConfig> overrides = Map.of();

    private volatile long version = 0L;

    /**
     * 覆盖式更新（version 单调校验）。
     * @return true=已采纳；false=版本过期被拒绝
     */
    public synchronized boolean update(long version, List<PullConfigPayload.TaskConfig> tasks) {
        if (version <= this.version) {
            return false;
        }
        Map<String, PullConfigPayload.TaskConfig> map = new HashMap<>();
        for (PullConfigPayload.TaskConfig task : tasks == null ? List.<PullConfigPayload.TaskConfig>of() : tasks) {
            if (task != null && task.getTaskCode() != null) {
                map.put(task.getTaskCode(), task);
            }
        }
        this.overrides = Map.copyOf(map);
        this.version = version;
        return true;
    }

    /**
     * 解析生效配置：control 覆盖优先，缺省回落代码内置默认。
     */
    public EffectiveConfig resolve(String taskCode, boolean defaultEnabled, long defaultTtlMs) {
        PullConfigPayload.TaskConfig override = overrides.get(taskCode);
        if (override == null) {
            return new EffectiveConfig(defaultEnabled, defaultTtlMs);
        }
        return new EffectiveConfig(override.isEnabled(),
                override.getTtlMs() > 0 ? override.getTtlMs() : defaultTtlMs);
    }

    /** 生效配置：开关 + 种子 TTL */
    public record EffectiveConfig(boolean enabled, long ttlMs) {
    }
}
