package com.zzh.stock_calculator.crawler.mq;

import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.message.PullConfigPayload;
import com.zzh.stock_calculator.monitor.PullLoopDispatchPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * PullLoopDispatchPort 的 crawler 侧实现（依赖倒置，docs/pull-loop-unification-design.md
 * L4）：monitor 看门狗经本适配器出向发布，避免 monitor → crawler 的反向依赖与心跳
 * 事件引用构成 Modulith 环。委托 TaskPublisher（crawler.mq 内部，含 confirms）。
 */
@Component
@RequiredArgsConstructor
public class PullLoopDispatchAdapter implements PullLoopDispatchPort {

    private final TaskPublisher publisher;

    @Override
    public void pushConfig(PullConfigPayload payload) {
        publisher.dispatchControl(MessageType.CONTROL_PULL_CONFIG, payload);
    }

    @Override
    public void dispatchSeed(String delayKey, long ttlMs) {
        publisher.dispatchSeed(delayKey, ttlMs);
    }

    @Override
    public void dispatchCalendarTask(String taskKey) {
        publisher.dispatchCalendarTask(taskKey);
    }
}
