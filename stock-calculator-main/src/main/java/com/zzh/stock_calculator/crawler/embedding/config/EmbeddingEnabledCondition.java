package com.zzh.stock_calculator.crawler.embedding.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 向量化功能装配条件（设计文档 §7.1）：
 * enabled=true 且 cloudflare.account-id / cloudflare.api-token 均非空才装配，
 * 避免「半配置状态」（开关开了但凭据缺失）导致运行期 401 抖动。
 * 凭据缺失但开关打开时输出一次 WARN 便于定位。
 */
@Slf4j
public class EmbeddingEnabledCondition implements Condition {

    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var env = context.getEnvironment();
        boolean enabled = env.getProperty("embedding.enabled", Boolean.class, Boolean.FALSE);
        String accountId = env.getProperty("embedding.cloudflare.account-id", "");
        String apiToken = env.getProperty("embedding.cloudflare.api-token", "");

        boolean credentialReady = !accountId.isBlank() && !apiToken.isBlank();
        if (enabled && !credentialReady && WARNED.compareAndSet(false, true)) {
            log.warn("embedding.enabled=true 但 CLOUDFLARE_ACCOUNT_ID / CLOUDFLARE_API_TOKEN 未配置，"
                    + "向量化功能整体关闭（Bean 不装配）");
        }
        return enabled && credentialReady;
    }
}
