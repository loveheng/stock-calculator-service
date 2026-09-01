package com.zzh.stock_calculator.vision.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 智能图片分析门面参数（vision.ai 前缀）。
 * 目前承载「图片哈希 -> 交易草稿 AI 结果缓存」的容量与存活时长配置。
 */
@Data
@ConfigurationProperties(prefix = "vision.ai")
public class VisionAiProperties {

    /** 结果缓存容量上限（key=图片 MD5，value=解析后的交易草稿列表） */
    private int resultCacheMaxSize = 128;

    /** 结果缓存写入后存活时长（命中直接返回，零 OCR/LLM 消耗） */
    private Duration resultCacheTtl = Duration.ofMinutes(30);
}
