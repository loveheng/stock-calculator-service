package com.zzh.stock_calculator.data.mq;

import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.message.PullConfigPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PullConfigCache 单测（设计不变量 5）：version 单调拒绝回灌、control 覆盖优先、
 * 缺省回落代码内置默认（冷启动不死锁）。
 */
class PullConfigCacheTest {

    private final PullConfigCache cache = new PullConfigCache();

    @Test
    @DisplayName("无覆盖时回落代码内置默认")
    void resolveFallsBackToDefaults() {
        PullConfigCache.EffectiveConfig config =
                cache.resolve(MqKey.TASK_CLS_PULL, true, 480_000L);
        assertTrue(config.enabled());
        assertEquals(480_000L, config.ttlMs());
    }

    @Test
    @DisplayName("覆盖生效：disabled + 自定义 TTL")
    void overrideApplies() {
        cache.update(100L, List.of(PullConfigPayload.TaskConfig.builder()
                .taskCode(MqKey.TASK_CLS_PULL).enabled(false).ttlMs(1_800_000L).build()));
        PullConfigCache.EffectiveConfig config =
                cache.resolve(MqKey.TASK_CLS_PULL, true, 480_000L);
        assertFalse(config.enabled());
        assertEquals(1_800_000L, config.ttlMs());
    }

    @Test
    @DisplayName("version 单调：旧快照拒绝，新源覆盖不影响未覆盖源")
    void staleVersionRejectedAndPartialOverride() {
        assertTrue(cache.update(100L, List.of(PullConfigPayload.TaskConfig.builder()
                .taskCode(MqKey.TASK_CLS_PULL).enabled(true).ttlMs(60_000L).build())));
        assertFalse(cache.update(100L, List.of()), "同 version 应被拒绝");
        assertFalse(cache.update(99L, List.of()), "旧 version 应被拒绝");

        PullConfigCache.EffectiveConfig cls = cache.resolve(MqKey.TASK_CLS_PULL, true, 480_000L);
        assertEquals(60_000L, cls.ttlMs(), "已覆盖源取覆盖值");
        PullConfigCache.EffectiveConfig ann = cache.resolve(
                MqKey.TASK_ANNOUNCEMENT_COLLECT, true, 3_600_000L);
        assertEquals(3_600_000L, ann.ttlMs(), "未覆盖源回落默认");
    }
}
