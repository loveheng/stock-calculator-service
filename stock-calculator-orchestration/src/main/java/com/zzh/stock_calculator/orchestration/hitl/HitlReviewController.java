package com.zzh.stock_calculator.orchestration.hitl;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * HITL 审核 API（步 7-1）：plan 详情 / 待审核清单 / 确认上架 / 废弃。
 * 管理面端点（非 copilot 工具面）——PassportFilter 服务级通行证统一守门，
 * 人工审核操作经后端管理界面持证调用（身份收敛在 main，编排器不见用户）。
 */
@RestController
@RequestMapping("/api/orchestration/hitl")
@RequiredArgsConstructor
public class HitlReviewController {

    private final HitlReviewService reviewService;

    /** plan 详情：DAG 全文 + param_schema + 最近 5 次真实执行摘要（status/耗时/节点成本） */
    @GetMapping("/plans/{planId}")
    public JsonNode planDetail(@PathVariable("planId") long planId) {
        return reviewService.planDetail(planId);
    }

    /** 待审核清单：draft + candidate（人工审核工作队列） */
    @GetMapping("/plans/pending")
    public JsonNode pendingReview() {
        ObjectNode out = new tools.jackson.databind.ObjectMapper().createObjectNode();
        out.set("plans", reviewService.pendingReview());
        return out;
    }

    /** 确认上架：candidate → verified（draft 拒绝直上，必须先过自动冒烟） */
    @PostMapping("/plans/{planId}/verify")
    public JsonNode verify(@PathVariable("planId") long planId) {
        var plan = reviewService.verify(planId);
        ObjectNode out = new tools.jackson.databind.ObjectMapper().createObjectNode();
        out.put("plan_id", plan.getId()).put("status", plan.getStatus());
        return out;
    }

    /** 废弃：draft/candidate → deprecated */
    @PostMapping("/plans/{planId}/deprecate")
    public JsonNode deprecate(@PathVariable("planId") long planId) {
        var plan = reviewService.deprecate(planId);
        ObjectNode out = new tools.jackson.databind.ObjectMapper().createObjectNode();
        out.put("plan_id", plan.getId()).put("status", plan.getStatus());
        return out;
    }

    /** 人工批准：waiting 实例的 hitl_wait 节点 done，断点续跑 */
    @PostMapping("/tasks/{taskId}/approve")
    public JsonNode approve(@PathVariable("taskId") long taskId,
                            @org.springframework.web.bind.annotation.RequestBody(required = false)
                            java.util.Map<String, String> body) {
        reviewService.approveTask(taskId, body == null ? null : body.get("note"));
        ObjectNode out = new tools.jackson.databind.ObjectMapper().createObjectNode();
        return out.put("task_id", taskId).put("action", "approved");
    }

    /** 人工拒绝：实例置 failed（断点保留可追溯） */
    @PostMapping("/tasks/{taskId}/reject")
    public JsonNode reject(@PathVariable("taskId") long taskId,
                           @org.springframework.web.bind.annotation.RequestBody(required = false)
                           java.util.Map<String, String> body) {
        reviewService.rejectTask(taskId, body == null ? null : body.get("note"));
        ObjectNode out = new tools.jackson.databind.ObjectMapper().createObjectNode();
        return out.put("task_id", taskId).put("action", "rejected");
    }
}
