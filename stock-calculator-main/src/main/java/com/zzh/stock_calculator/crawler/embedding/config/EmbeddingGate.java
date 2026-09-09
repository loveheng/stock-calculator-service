package com.zzh.stock_calculator.crawler.embedding.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 向量化运行期门控（R1 重构，2026-09-09 用户拍板；替代原 EmbeddingEnabledCondition
 * 构建期条件装配）。
 *
 * <p>设计动机：Spring AOT 在 native 构建期固化所有 {@code @Conditional} 判定——构建环境
 * 无 CF 凭据时 Bean 定义被物理剔除，运行期注入凭据无法恢复（原 native 变体向量化不可用
 * 的根因）。改为「Bean 一律注册 + 运行期门控」：重 Bean（OpenAiEmbedModel/PgVectorStore）
 * 依赖全局 lazy-initialization 与调用方门控实现「未启用不实例化」，native/JVM 行为一致。
 *
 * <p>判定语义与原条件一致：enabled=true 且 account-id/api-token 均非空才可用，
 * 避免「半配置状态」导致运行期 401 抖动；凭据缺失但开关打开时 WARN 一次便于定位。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingGate {

    private final EmbeddingProperties properties;

    /** WARN 一次标志（实例级，便于单测隔离） */
    private final AtomicBoolean warned = new AtomicBoolean(false);

    /** 向量化是否可用（运行期判定）：总开关开启且 CF 凭据齐备 */
    public boolean isAvailable() {
        String accountId = properties.getCloudflare().getAccountId();
        String apiToken = properties.getCloudflare().getApiToken();
        boolean credentialReady = accountId != null && !accountId.isBlank()
                && apiToken != null && !apiToken.isBlank();
        if (properties.isEnabled() && !credentialReady && warned.compareAndSet(false, true)) {
            log.warn("embedding.enabled=true 但 CLOUDFLARE_ACCOUNT_ID / CLOUDFLARE_API_TOKEN 未配置，"
                    + "向量化功能整体关闭（重 Bean 不实例化）");
        }
        return properties.isEnabled() && credentialReady;
    }
}
