package com.zzh.stock_calculator.notify.service;

import com.zzh.stock_calculator.notify.entity.CapabilityRequestEntity;
import com.zzh.stock_calculator.notify.repository.CapabilityRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * capability 在途请求登记/匹配（docs/notify/design.md §六：临时关联表 + 超时兜底）：
 * - register：capability 请求发出时登记 pending（deadline 默认 60s）；
 * - resolve：结果回流按 traceId 取走并删除（唯一键防重复回流重复触发）；
 * - degrade：超时降级通知 + pending 清理由看门狗驱动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CapabilityPendingService {

    /** 结果回流等待死线：能力请求属轻量查询，60s 未回即降级 */
    private static final long DEFAULT_DEADLINE_MS = 60_000L;

    private final CapabilityRequestRepository repository;

    /** 登记在途请求（traceId 唯一；重复登记以最新 deadline 覆盖） */
    @Transactional
    public void register(String traceId, Long reminderId) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime deadline = now.plus(DEFAULT_DEADLINE_MS, java.time.temporal.ChronoUnit.MILLIS);
        repository.findByTraceId(traceId).ifPresentOrElse(existing -> {
            existing.setDeadline(deadline);
            repository.save(existing);
        }, () -> repository.save(CapabilityRequestEntity.builder()
                .traceId(traceId)
                .reminderId(reminderId)
                .deadline(deadline)
                .createdAt(now)
                .build()));
    }

    /** 结果回流匹配：取走即删（重复回流第二次找不到，天然幂等）；reminder 校验由调用方做 */
    @Transactional
    public CapabilityRequestEntity resolve(String traceId) {
        CapabilityRequestEntity pending = repository.findByTraceId(traceId).orElse(null);
        if (pending != null) {
            repository.delete(pending);
        }
        return pending;
    }
}
