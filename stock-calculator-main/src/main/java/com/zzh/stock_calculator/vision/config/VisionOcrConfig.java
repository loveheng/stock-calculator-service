package com.zzh.stock_calculator.vision.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * vision 智能识别组件配置：注册 OcrProperties（vision.ocr 前缀）与 VisionAiProperties（vision.ai 前缀）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({OcrProperties.class, VisionAiProperties.class})
public class VisionOcrConfig {
}
