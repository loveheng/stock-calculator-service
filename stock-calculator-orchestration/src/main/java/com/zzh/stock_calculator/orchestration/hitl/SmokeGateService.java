package com.zzh.stock_calculator.orchestration.hitl;

import com.zzh.stock_calculator.orchestration.entity.PlanEntity;
import com.zzh.stock_calculator.orchestration.entity.TaskInstanceEntity;
import com.zzh.stock_calculator.orchestration.mq.TaskMessageSender;
import com.zzh.stock_calculator.orchestration.repository.PlanRepository;
import com.zzh.stock_calculator.orchestration.repository.TaskInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 自动冒烟闸门（P1-5，D10 流水线第一环：draft → 自动冒烟 → candidate）。
 * <p>此前全仓无写 candidate 的代码路径——draft 落库后没人推冒烟，人工上架被
 * 「仅 candidate 可 verified」硬卡死，流水线第一环实际断裂。本类补齐：
 * <ul>
 *   <li>triggerSmoke：draft 落库后即刻创建冒烟实例（params.purpose=smoke + dry_run=true，
 *       Executor 冒烟模式只验结构不触外部系统）并经 MQ 下发 TaskRunner 链路；</li>
 *   <li>onSmokeTerminal：冒烟实例终态回调——done → draft 升 candidate；failed → 保持
 *       draft + needs_review=TRUE + reviewer_note 留痕，交人工复核。</li>
 * </ul>
 * 复用实例（非冒烟）的终态走 TaskRunnerListener 原路径（终态事件 + use_count 补记），互不干扰。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmokeGateService {

    private final TaskInstanceRepository taskInstanceRepository;
    private final PlanRepository planRepository;
    private final TaskMessageSender taskMessageSender;

    private final ObjectMapper om = new ObjectMapper();

    /** draft 落库后推冒烟：独立实例（purpose=smoke + dry_run），traceId 前缀 smoke-p 便于排查 */
    public void triggerSmoke(PlanEntity plan) {
        String traceId = "smoke-p" + plan.getId() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        ObjectNode params = om.createObjectNode()
                .put("purpose", TaskInstanceEntity.PURPOSE_SMOKE)
                .put("dry_run", true);
        TaskInstanceEntity instance = TaskInstanceEntity.builder()
                .planId(plan.getId())
                .planDagSnapshot(plan.getPlanDag())
                .traceId(traceId)
                .userId("system-smoke")
                .params(params)
                .build();
        TaskInstanceEntity saved = taskInstanceRepository.save(instance);
        taskMessageSender.sendRunRequest(saved.getId(), traceId);
        log.info("[smoke] plan {} 冒烟实例已下发 taskId={} traceId={}", plan.getId(), saved.getId(), traceId);
    }

    /**
     * 冒烟终态回调（TaskRunnerListener 调用）。仅处理 draft/candidate 态——期间被人工
     * deprecated/rejected 的 plan 不回改（人工决策优先于自动升格）。
     */
    @Transactional
    public void onSmokeTerminal(long taskId, boolean done) {
        TaskInstanceEntity instance = taskInstanceRepository.findById(taskId).orElse(null);
        if (instance == null) {
            log.warn("[smoke] 冒烟实例 {} 不存在，忽略终态回调", taskId);
            return;
        }
        PlanEntity plan = planRepository.findById(instance.getPlanId()).orElse(null);
        if (plan == null) {
            log.warn("[smoke] plan {} 不存在，忽略冒烟终态 taskId={}", instance.getPlanId(), taskId);
            return;
        }
        if (!"draft".equals(plan.getStatus())) {
            log.info("[smoke] plan {} 状态 {} 非 draft，冒烟终态不回改 taskId={}",
                    plan.getId(), plan.getStatus(), taskId);
            return;
        }
        plan.setUpdatedAt(LocalDateTime.now());
        if (done) {
            plan.setStatus("candidate");
            planRepository.save(plan);
            log.info("[smoke] plan {} 冒烟通过 draft→candidate（冒烟实例 taskId={}）", plan.getId(), taskId);
        } else {
            plan.setNeedsReview(true);
            plan.setReviewerNote("自动冒烟未通过（实例 taskId=" + taskId + " traceId="
                    + instance.getTraceId() + "），待人工复核");
            planRepository.save(plan);
            log.warn("[smoke] plan {} 冒烟未通过，保持 draft 待人工复核（taskId={}）", plan.getId(), taskId);
        }
    }
}
