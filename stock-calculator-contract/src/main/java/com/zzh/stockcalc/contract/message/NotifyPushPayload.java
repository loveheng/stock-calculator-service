package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * notify.push 的 payload（docs/notify/design.md §六）：notify → main 的组装后通知，
 * main push 消费者落 push_message 并经 Web Push / SSE 触达用户。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotifyPushPayload {

    /** 归属用户（copilot 持会话身份传入） */
    private String userId;

    /** 提醒来源（reminder_id；种子型直通时由 notify 回填） */
    private Long reminderId;

    /** 通知标题（如「9:30 快报」） */
    private String title;

    /** 通知正文（模板轻量拼接结果，不引入模板引擎） */
    private String body;

    /** 点击跳转路径（可空，透传给前端通知点击） */
    private String url;
}
