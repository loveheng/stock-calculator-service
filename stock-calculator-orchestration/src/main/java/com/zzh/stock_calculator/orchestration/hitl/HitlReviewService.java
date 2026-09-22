package com.zzh.stock_calculator.orchestration.hitl;

import com.zzh.stock_calculator.orchestration.entity.PlanEntity;
import com.zzh.stock_calculator.orchestration.entity.TaskInstanceEntity;
import com.zzh.stock_calculator.orchestration.repository.PlanRepository;
import com.zzh.stock_calculator.orchestration.repository.TaskInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * HITL 审核服务（步 7-1，D10 状态机守门人）：
 * <ul>
 *   <li>plan 详情：DAG 全文 + 最近 N 次真实执行的节点耗时摘要（人工判断依据）；</li>
 *   <li>确认上架：candidate → verified（仅 candidate 可上架，draft 必须先过自动冒烟）；</li>
 *   <li>废弃：draft/candidate → deprecated。</li>
 * </ul>
 * 状态机非法流转一律拒绝（不静默幂等）：verified/deprecated 为终态不可再变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HitlReviewService {

    /** 待复核 plan 详情附带的最近执行记录数 */
    private static final int RECENT_RUNS = 5;

    private final PlanRepository planRepository;
    private final TaskInstanceRepository taskInstanceRepository;
    private final com.zzh.stock_calculator.orchestration.executor.Executor executor;
    private final ObjectMapper om = new ObjectMapper();

    /** plan 详情（DAG + 最近真实执行摘要：status/耗时/节点成本，人工审核判断依据） */
    @Transactional(readOnly = true)
    public ObjectNode planDetail(long planId) {
        PlanEntity plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("plan 不存在: " + planId));
        ObjectNode detail = om.createObjectNode();
        detail.put("plan_id", plan.getId());
        detail.put("intent_text", plan.getIntentText());
        detail.put("status", plan.getStatus());
        detail.put("needs_review", plan.getNeedsReview());
        detail.put("use_count", plan.getUseCount());
        detail.set("param_schema", plan.getParamSchema());
        detail.set("plan_dag", plan.getPlanDag());
        ArrayNode runs = detail.putArray("recent_runs");
        List<TaskInstanceEntity> recent = taskInstanceRepository.findByPlanIdOrderByUpdatedAtDesc(
                planId, PageRequest.of(0, RECENT_RUNS));
        for (TaskInstanceEntity run : recent) {
            ObjectNode r = om.createObjectNode();
            r.put("task_id", run.getId());
            r.put("trace_id", run.getTraceId());
            r.put("status", run.getStatus());
            r.put("updated_at", run.getUpdatedAt() == null ? "" : run.getUpdatedAt().toString());
            r.set("node_states", run.getNodeStates());
            runs.add(r);
        }
        return detail;
    }

    /** 确认上架：candidate → verified；draft 未过冒烟拒绝直上（D10 流水线顺序硬约束） */
    @Transactional
    public PlanEntity verify(long planId) {
        PlanEntity plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("plan 不存在: " + planId));
        if (!"candidate".equals(plan.getStatus())) {
            throw new IllegalStateException("plan " + planId + " 状态为 " + plan.getStatus()
                    + "，仅 candidate 可上架 verified（draft 须先过自动冒烟）");
        }
        plan.setStatus("verified");
        plan.setUpdatedAt(java.time.LocalDateTime.now());
        PlanEntity saved = planRepository.save(plan);
        log.info("[hitl] plan {} 上架 verified", planId);
        return saved;
    }

    /** 废弃（draft/candidate 均可弃；verified 弃用走 needs_review 触发重规划，不在此收） */
    @Transactional
    public PlanEntity deprecate(long planId) {
        PlanEntity plan = planRepository.findById(planId)
                .orElseThrow(() -> new IllegalArgumentException("plan 不存在: " + planId));
        if ("verified".equals(plan.getStatus()) || "deprecated".equals(plan.getStatus())) {
            throw new IllegalStateException("plan " + planId + " 状态为 " + plan.getStatus() + "，不可废弃");
        }
        plan.setStatus("deprecated");
        plan.setUpdatedAt(java.time.LocalDateTime.now());
        PlanEntity saved = planRepository.save(plan);
        log.info("[hitl] plan {} 废弃 deprecated", planId);
        return saved;
    }

    /** 待审核清单：draft + candidate（needs_review 的 verified 也提示复看） */
    @Transactional(readOnly = true)
    public ArrayNode pendingReview() {
        ArrayNode out = om.createArrayNode();
        planRepository.findByStatusInOrderByIdDesc(List.of("draft", "candidate")).forEach(p -> {
            ObjectNode n = om.createObjectNode();
            n.put("plan_id", p.getId());
            n.put("intent_text", p.getIntentText());
            n.put("status", p.getStatus());
            n.put("needs_review", p.getNeedsReview());
            out.add(n);
        });
        return out;
    }

    // ========== HITL 运行期挂起回调（hitl_wait 节点，mq_wait 机制复用） ==========

    /**
     * 人工批准回调（步 7-1）：waiting 实例的 hitl_wait 节点标记 done（携带审核决策），
     * 重入 run() 从断点续跑——与 mq_wait 唤醒同一套断点机制。
     */
    @Transactional
    public void approveTask(long taskId, String reviewerNote) {
        resumeWaiting(taskId, true, reviewerNote);
    }

    /** 人工拒绝回调：实例置 failed（节点断点保留 error=审核拒绝，可追溯不可续跑） */
    @Transactional
    public void rejectTask(long taskId, String reviewerNote) {
        resumeWaiting(taskId, false, reviewerNote);
    }

    private void resumeWaiting(long taskId, boolean approved, String note) {
        TaskInstanceEntity instance = taskInstanceRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("任务实例不存在: " + taskId));
        if (!TaskInstanceEntity.ST_WAITING.equals(instance.getStatus())) {
            throw new IllegalStateException("实例 " + taskId + " 状态为 " + instance.getStatus() + "，非挂起不可审核");
        }
        String hitlNodeId = findHitlWaitNode(instance);
        if (hitlNodeId == null) {
            throw new IllegalStateException("挂起实例 " + taskId + " 无 hitl_wait 节点，不可人工审核");
        }
        if (approved) {
            var states = instance.getNodeStates();
            if (states instanceof tools.jackson.databind.node.ObjectNode o) {
                o.set(hitlNodeId, om.createObjectNode()
                        .put("status", "done").put("cost_ms", 0)
                        .set("output", om.createObjectNode()
                                .put("approved", true)
                                .put("reviewer_note", note == null ? "" : note)));
            }
            instance.setNodeStates(states);
            instance.setStatus(TaskInstanceEntity.ST_RUNNING);
            instance.setWaitDeadline(null);
            taskInstanceRepository.save(instance);
            log.info("[hitl] 实例 {} 人工批准续跑 node={} taskId", instance.getId(), hitlNodeId);
            executor.run(instance);
        } else {
            instance.setStatus(TaskInstanceEntity.ST_FAILED);
            instance.setWaitDeadline(null);
            taskInstanceRepository.save(instance);
            log.info("[hitl] 实例 {} 人工拒绝（note={}）", instance.getId(), note);
        }
    }

    /** 找 node_states 中尚无状态记录的 hitl_wait 节点（挂起断点定位，与 mq_wait 同款） */
    private String findHitlWaitNode(TaskInstanceEntity instance) {
        for (JsonNode node : instance.getPlanDagSnapshot().path("nodes")) {
            String nid = node.path("id").asText("");
            if ("hitl_wait".equals(node.path("type").asText("")) && !instance.getNodeStates().has(nid)) {
                return nid;
            }
        }
        return null;
    }
}
