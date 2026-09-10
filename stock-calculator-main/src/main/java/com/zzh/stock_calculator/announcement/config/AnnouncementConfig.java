package com.zzh.stock_calculator.announcement.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * announcement 域配置注册（仿 EmbeddingConfig 的 @EnableConfigurationProperties 注册式，
 * 无包扫描）。
 */
@Configuration
@EnableConfigurationProperties(AnnouncementProperties.class)
public class AnnouncementConfig {
}
