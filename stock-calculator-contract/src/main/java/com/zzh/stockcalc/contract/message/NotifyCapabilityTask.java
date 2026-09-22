package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * task.notify.capability 的 payload（docs/notify/design.md §六）：
 * notify → main 的能力请求（action.kind=capability 直通）。
 * capabilityName 为 main 侧注册的能力名；params 透传业务参数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotifyCapabilityTask {

    /** 发起方 reminder（结果回流后状态校验，done 则丢弃） */
    private Long reminderId;

    /** 能力名（如 stock_analysis；main 能力消费者按此路由） */
    private String capabilityName;

    /** 业务参数（如 stockId、action 语义键值对） */
    private Map<String, Object> params;
}
