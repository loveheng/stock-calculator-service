package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * result.notify.capability 的 payload（docs/notify/design.md §六）：
 * main → notify 的能力结果回流，notify 按 traceId 关联 pending 请求；
 * ok=false 时 notify 走降级通知（「数据暂不可用」）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotifyCapabilityResult {

    /** 发起方 reminder（与请求 payload 的 reminderId 一致） */
    private Long reminderId;

    /** 成功与否 */
    private boolean ok;

    /** 摘要 + 能力结果（成功时为组装素材；失败时为错误摘要） */
    private String summary;

    /** 结果明细（可选，结构化结果供组装扩展） */
    private Object detail;
}
