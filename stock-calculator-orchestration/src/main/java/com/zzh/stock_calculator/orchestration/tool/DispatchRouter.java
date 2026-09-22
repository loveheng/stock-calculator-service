package com.zzh.stock_calculator.orchestration.tool;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * dispatch 确定性分流器（步 5 定案，decisions §misc：规则起步勿上分类模型）：
 * - 意图文本对 plannable 工具做确定性打分（工具名命中 > 领域名命中）；
 * - 唯一高分胜出 → 按该工具 execution_mode 分流（sync 直调 / async_long 转任务）；
 * - 无命中或并列 → 低置信，返回候选清单澄清（不硬路由）；
 * - 误判样本回流本类规则并补单测。
 */
@Component
public class DispatchRouter {

    public enum Verdict {SYNC_DIRECT, TASK, CLARIFY}

    /** 分流结果：verdict + 命中工具（SYNC_DIRECT/TASK）+ 候选清单（CLARIFY） */
    public record RouteResult(Verdict verdict, ToolDescriptor tool, List<ToolDescriptor> candidates) {}

    private static final int SCORE_NAME = 100;
    private static final int SCORE_DOMAIN = 10;

    public RouteResult route(String intentText, List<ToolDescriptor> plannable) {
        String text = intentText == null ? "" : intentText.toLowerCase();
        record Scored(ToolDescriptor tool, int score) {}
        List<Scored> scored = new ArrayList<>();
        for (ToolDescriptor t : plannable) {
            int s = 0;
            if (t.getToolName() != null && text.contains(t.getToolName().toLowerCase())) {
                s += SCORE_NAME;
            }
            if (t.getDomain() != null && !t.getDomain().isBlank() && text.contains(t.getDomain().toLowerCase())) {
                s += SCORE_DOMAIN;
            }
            if (s > 0) {
                scored.add(new Scored(t, s));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed());
        if (scored.isEmpty()) {
            return new RouteResult(Verdict.CLARIFY, null, List.of());
        }
        // 并列即低置信（同分视为不可判定，宁可澄清不可硬路由）
        int top = scored.get(0).score();
        List<ToolDescriptor> tied = scored.stream().filter(x -> x.score() == top).map(Scored::tool).toList();
        if (tied.size() > 1) {
            return new RouteResult(Verdict.CLARIFY, null, tied);
        }
        ToolDescriptor winner = tied.get(0);
        return new RouteResult(ToolRegistry.EM_SYNC.equals(winner.getExecutionMode())
                ? Verdict.SYNC_DIRECT : Verdict.TASK, winner, List.of(winner));
    }
}
