package com.zzh.stock_calculator.data.ingest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * ingest 角色装配（阶段 5）：与 collector/worker 同款条件门控；HTTP 端点 + parser 插件
 * 均挂 datasvc.ingest.enabled=true，默认关闭。
 */
@Configuration
@ConditionalOnProperty(prefix = "datasvc.ingest", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(IngestProperties.class)
public class IngestConfig {
}
