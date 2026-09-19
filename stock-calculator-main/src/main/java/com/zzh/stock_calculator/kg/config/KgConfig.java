package com.zzh.stock_calculator.kg.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * kg 域配置注册（仿 AnnouncementConfig 的 @EnableConfigurationProperties 注册式）。
 */
@Configuration
@EnableConfigurationProperties(KgProperties.class)
public class KgConfig {
}
