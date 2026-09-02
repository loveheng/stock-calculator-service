package com.zzh.stock_calculator.vision.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 智能图片分析门面参数（vision.ai 前缀）。
 * 目前承载「图片哈希 -> 交易草稿 AI 结果缓存」的 Redis 存活时长配置。
 */
@Data
@ConfigurationProperties(prefix = "vision.ai")
public class VisionAiProperties {

    /** 结果缓存的 Redis 存活时长（key=vision:ai:draft:<MD5>，命中直接返回，零 OCR/LLM 消耗） */
    private Duration resultCacheTtl = Duration.ofMinutes(30);
}
