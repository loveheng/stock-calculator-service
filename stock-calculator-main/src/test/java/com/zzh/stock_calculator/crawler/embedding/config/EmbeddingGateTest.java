package com.zzh.stock_calculator.crawler.embedding.config;

import com.zzh.llm.LlmRegistry;
import com.zzh.llm.LlmTierProperties;
import com.zzh.llm.EmbedSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EmbeddingGate 运行期门控单测（无 Spring 上下文）：
 * enabled + embed tier 凭据齐备判定（cloudflare provider 走 account-id/api-token）。
 */
class EmbeddingGateTest {

    private static LlmRegistry registryWithCredentials(String accountId, String apiToken) {
        EmbedSpec spec = new EmbedSpec();
        spec.setProvider("cloudflare");
        spec.setAccountId(accountId);
        spec.setApiToken(apiToken);
        spec.setModel("@cf/baai/bge-m3");
        LlmTierProperties tierProps = new LlmTierProperties();
        tierProps.getEmbeddings().put("embed", spec);
        return new LlmRegistry(tierProps);
    }

    private static EmbeddingProperties props(boolean enabled) {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setEnabled(enabled);
        return properties;
    }

    @Test
    @DisplayName("enabled=true + 凭据齐备 → 可用")
    void availableWhenFullyConfigured() {
        assertThat(new EmbeddingGate(props(true), registryWithCredentials("acc", "tok")).isAvailable()).isTrue();
    }

    @Test
    @DisplayName("enabled=false（即使凭据齐备）→ 不可用")
    void unavailableWhenDisabled() {
        assertThat(new EmbeddingGate(props(false), registryWithCredentials("acc", "tok")).isAvailable()).isFalse();
    }

    @Test
    @DisplayName("凭据缺失（null/空白）→ 不可用，避免半配置状态运行期 401 抖动")
    void unavailableWhenCredentialsMissing() {
        assertThat(new EmbeddingGate(props(true), registryWithCredentials(null, "tok")).isAvailable()).isFalse();
        assertThat(new EmbeddingGate(props(true), registryWithCredentials("acc", null)).isAvailable()).isFalse();
        assertThat(new EmbeddingGate(props(true), registryWithCredentials(" ", "tok")).isAvailable()).isFalse();
        assertThat(new EmbeddingGate(props(true), registryWithCredentials("acc", "")).isAvailable()).isFalse();
    }

    @Test
    @DisplayName("重复判定幂等（warn-once 不影响结果）")
    void repeatedChecksStable() {
        EmbeddingGate gate = new EmbeddingGate(props(true), registryWithCredentials("acc", "tok"));
        assertThat(gate.isAvailable()).isTrue();
        assertThat(gate.isAvailable()).isTrue();

        EmbeddingGate closed = new EmbeddingGate(props(true), registryWithCredentials(null, "tok"));
        assertThat(closed.isAvailable()).isFalse();
        assertThat(closed.isAvailable()).isFalse();
    }
}
