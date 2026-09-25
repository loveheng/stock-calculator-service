package com.zzh.stock_calculator.broker.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * broker 域配置注册（free-canvas v3，仿 AnnouncementConfig 的
 * &#64;EnableConfigurationProperties 注册式）。
 */
@Configuration
@EnableConfigurationProperties(BrokerProperties.class)
public class BrokerConfig {
}
