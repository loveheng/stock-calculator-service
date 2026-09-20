package com.zzh.stock_calculator.mcp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 调度开关（M1b RSS 轮询）：mcp 模块首个 @Scheduled 组件在此启用。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
