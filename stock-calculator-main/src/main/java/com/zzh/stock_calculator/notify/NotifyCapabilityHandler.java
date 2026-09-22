package com.zzh.stock_calculator.notify;

import com.zzh.stockcalc.contract.message.NotifyCapabilityResult;
import com.zzh.stockcalc.contract.message.NotifyCapabilityTask;

/**
 * notify 能力请求处理器 SPI（docs/notify/design.md §六：main 能力消费者执行）：
 * 领域按需注册 @Component 实现（如查形态走 stock-mcp 经纪人工具），消费者按
 * capabilityName 路由；无匹配 handler 或执行异常 → ok=false 回流（notify 侧降级通知）。
 * Modulith 边界：SPI 接口放 notify 基包，实现可跨域引用对方基包公开类型。
 */
public interface NotifyCapabilityHandler {

    /** 能力名（与 NotifyCapabilityTask.capabilityName 对应，如 stock_analysis） */
    String capability();

    /** 执行能力请求：返回回流结果；抛异常等同 ok=false（消费者兜底转降级） */
    NotifyCapabilityResult handle(NotifyCapabilityTask task);
}
