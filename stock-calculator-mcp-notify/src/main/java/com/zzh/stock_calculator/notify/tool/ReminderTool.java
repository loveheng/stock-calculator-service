package com.zzh.stock_calculator.notify.tool;

import com.zzh.stock_calculator.notify.entity.ReminderEntity;
import com.zzh.stock_calculator.notify.service.ReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * reminder MCP 工具面（docs/notify/design.md §七）：LLM 对话内登记/查询/修改/删除提醒（N4）。
 * 用户身份由入参 userId 承载（copilot 持会话身份传入）；工具面本地裸跑无鉴权（与 stock-mcp 同口径）。
 * 修改=删除重建（N6）：reminder_update 内部走 cancel + create 两步表达。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderTool {

    private final ReminderService reminderService;

    @Tool(name = "reminder_create", description = "登记个人提醒：at_time（定时到点，spec 含 nextFireAt/repeat）或 on_event（事件触发，spec 必带非空 filter）；action 为 {kind:text|capability, payload}")
    public String reminderCreate(
            @ToolParam(description = "用户 ID（copilot 持会话身份传入）") String userId,
            @ToolParam(description = "触发类型：at_time=定时 / on_event=事件") String triggerType,
            @ToolParam(description = "触发规格 JSON：at_time={\"nextFireAt\":\"2026-09-22T01:30:00Z\",\"repeat\":\"once|daily|weekly\"}；on_event={\"eventType\":\"...\",\"filter\":{...}}") String triggerSpec,
            @ToolParam(description = "动作 JSON：{\"kind\":\"text\",\"payload\":{...}} 或 {\"kind\":\"capability\",\"payload\":{\"capabilityName\":\"...\"}}") String action) {
        try {
            ReminderEntity entity = reminderService.create(userId, triggerType, triggerSpec, action);
            return "提醒登记成功，reminder_id=" + entity.getId();
        } catch (IllegalArgumentException e) {
            return "登记失败：" + e.getMessage();
        }
    }

    @Tool(name = "reminder_list", description = "查询当前用户的活跃提醒清单（含触发/被限幅计数）")
    public String reminderList(
            @ToolParam(description = "用户 ID") String userId) {
        List<ReminderEntity> list = reminderService.listActive(userId);
        if (list.isEmpty()) {
            return "当前无活跃提醒";
        }
        StringBuilder sb = new StringBuilder("共 " + list.size() + " 条活跃提醒：\n");
        for (ReminderEntity r : list) {
            sb.append("- id=").append(r.getId())
                    .append(" trigger=").append(r.getTriggerType())
                    .append(" fired=").append(r.getFireCount())
                    .append(" suppressed=").append(r.getSuppressedCount())
                    .append("\n");
        }
        return sb.toString();
    }

    @Tool(name = "reminder_update", description = "修改提醒（删除重建语义：cancel 旧 reminder 后按新内容重建，返回新 reminder_id）")
    public String reminderUpdate(
            @ToolParam(description = "要修改的 reminder ID") Long reminderId,
            @ToolParam(description = "新触发类型：at_time / on_event") String triggerType,
            @ToolParam(description = "新触发规格 JSON") String triggerSpec,
            @ToolParam(description = "新动作 JSON：{kind:text|capability, payload}") String action) {
        try {
            ReminderEntity entity = reminderService.update(reminderId, triggerType, triggerSpec, action);
            return "提醒已重建：旧 id=" + reminderId + " 已取消，新 reminder_id=" + entity.getId();
        } catch (IllegalArgumentException e) {
            return "修改失败：" + e.getMessage();
        }
    }

    @Tool(name = "reminder_cancel", description = "取消提醒：置 cancelled，delay 队列残留种子到期时按 status 幂等跳过")
    public String reminderCancel(
            @ToolParam(description = "要取消的 reminder ID") Long reminderId) {
        try {
            reminderService.cancel(reminderId);
            return "提醒已取消：reminder_id=" + reminderId;
        } catch (IllegalArgumentException e) {
            return "取消失败：" + e.getMessage();
        }
    }
}
