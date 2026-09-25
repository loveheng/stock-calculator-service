package com.zzh.stock_calculator.copilot.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Copilot 记忆链配置注册（copilot.memory.*，@EnableConfigurationProperties 模式）。
 */
@Configuration
@EnableConfigurationProperties(CopilotMemoryProperties.class)
public class CopilotMemoryConfig {
}
