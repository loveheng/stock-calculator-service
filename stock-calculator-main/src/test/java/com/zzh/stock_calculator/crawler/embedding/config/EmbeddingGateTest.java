package com.zzh.stock_calculator.crawler.embedding.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EmbeddingGate 运行期门控单测（无 Spring 上下文）：
 * enabled + 凭据齐备三重判定，与原 EmbeddingEnabledCondition 语义一致（R1 迁移等价性）。
 */
class EmbeddingGateTest {

    private static EmbeddingProperties props(boolean enabled, String accountId, String apiToken) {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setEnabled(enabled);
        properties.getCloudflare().setAccountId(accountId);
        properties.getCloudflare().setApiToken(apiToken);
        return properties;
    }

    @Test
    @DisplayName("enabled=true + 凭据齐备 → 可用")
    void availableWhenFullyConfigured() {
        assertThat(new EmbeddingGate(props(true, "acc", "tok")).isAvailable()).isTrue();
    }

    @Test
    @DisplayName("enabled=false（即使凭据齐备）→ 不可用")
    void unavailableWhenDisabled() {
        assertThat(new EmbeddingGate(props(false, "acc", "tok")).isAvailable()).isFalse();
    }

    @Test
    @DisplayName("凭据缺失（null/空白）→ 不可用，避免半配置状态运行期 401 抖动")
    void unavailableWhenCredentialsMissing() {
        assertThat(new EmbeddingGate(props(true, null, "tok")).isAvailable()).isFalse();
        assertThat(new EmbeddingGate(props(true, "acc", null)).isAvailable()).isFalse();
        assertThat(new EmbeddingGate(props(true, " ", "tok")).isAvailable()).isFalse();
        assertThat(new EmbeddingGate(props(true, "acc", "")).isAvailable()).isFalse();
    }

    @Test
    @DisplayName("重复判定幂等（warn-once 不影响结果）")
    void repeatedChecksStable() {
        EmbeddingGate gate = new EmbeddingGate(props(true, "acc", "tok"));
        assertThat(gate.isAvailable()).isTrue();
        assertThat(gate.isAvailable()).isTrue();

        EmbeddingGate closed = new EmbeddingGate(props(true, null, "tok"));
        assertThat(closed.isAvailable()).isFalse();
        assertThat(closed.isAvailable()).isFalse();
    }
}
