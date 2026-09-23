package com.zzh.stock_calculator.orchestration.mq;

import com.zzh.stock_calculator.orchestration.entity.TaskInstanceEntity;
import com.zzh.stock_calculator.orchestration.hitl.SmokeGateService;
import com.zzh.stock_calculator.orchestration.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 实例终态统一处理器（自 TaskRunnerListener 抽取，供多处 run 完成点复用）：
 * <ul>
 *   <li>冒烟实例（params.purpose=smoke）→ SmokeGateService 升格状态机（done→candidate /
 *       failed→needs_review），不发终态事件、不计 use_count；</li>
 *   <li>普通实例 done → 按 plan_id 补记 use_count（P1-2 口径=成功复用）；</li>
 *   <li>done/failed → 回发 task.completed./task.failed.（mq_wait 唤醒 + main SSE/GC）。</li>
 * </ul>
 * 调用方：TaskRunnerListener（MQ 消费）、InstanceRecoveryRunner（重启恢复）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskTerminalHandler {

    private final PlanRepository planRepository;
    private final SmokeGateService smokeGateService;
    private final TaskMessageSender taskMessageSender;
    private final ObjectMapper om = new ObjectMapper();

    public void handleTerminal(TaskInstanceEntity after) {
        String finalStatus = after.getStatus();
        // P1-5 冒烟实例：终态走 plan 升格状态机，不走普通终态面
        if (after.isSmokeRun()) {
            smokeGateService.onSmokeTerminal(after.getId(), TaskInstanceEntity.ST_DONE.equals(finalStatus));
            return;
        }
        // P1-2 use_count 终态补记：口径=成功复用（done），失败不计，waiting 无终态不补记
        if (TaskInstanceEntity.ST_DONE.equals(finalStatus)) {
            planRepository.updateUseStats(after.getPlanId());
        }
        // 终态事件回发（mq_wait 唤醒 + main SSE/GC 数据源）
        if (TaskInstanceEntity.ST_DONE.equals(finalStatus) || TaskInstanceEntity.ST_FAILED.equals(finalStatus)) {
            String routing = (TaskInstanceEntity.ST_DONE.equals(finalStatus)
                    ? com.zzh.stockcalc.contract.MqKey.TASK_COMPLETED_PREFIX
                    : com.zzh.stockcalc.contract.MqKey.TASK_FAILED_PREFIX) + "orchestration";
            ObjectNode out = om.createObjectNode()
                    .put("correlation_id", after.getTraceId())
                    .put("task_id", String.valueOf(after.getId()))
                    .put("status", finalStatus);
            taskMessageSender.send(routing, after.getTraceId(), out);
        }
    }
}
