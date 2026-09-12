package com.zzh.stock_calculator.monitor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管线告警事件（monitor 基包 = 跨域 API，auth 侧邮件监听器消费）。
 * 每个触发阈值的检查项一条事件；冷却去重在发布端完成，监听器只管渲染发送。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineAlertEvent {

    /** 告警类别（DATA_DOWN / BACKLOG / DEAD_Q / PENDING_AGE / BROKER_UNREACHABLE） */
    private String kind;

    /** 邮件主题行（监听器原样使用） */
    private String subject;

    /** 邮件正文（发布端渲染，监听器不加工） */
    private String body;

    /** 触发时刻（epoch 毫秒） */
    private Long occurredAtEpochMs;
}
