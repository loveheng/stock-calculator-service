package com.zzh.stock_calculator.search.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * search 域配置注册（@EnableConfigurationProperties 注册式，无包扫描；AnnouncementConfig 同款）。
 */
@Configuration
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfig {
}
