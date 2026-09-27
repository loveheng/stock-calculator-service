package com.zzh.stock_calculator.orchestration;

import com.zzh.stock_calculator.orchestration.tool.McpBrokerClientProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * McpBrokerClientProvider 失败面守卫（2026-09-27 启动解耦档 2）：这里只测「连不上」的行为，
 * 成功路径需要有真实 :18081，归 native 冒烟与联调。
 * 契约：① 握手失败即刻抛（调用侧按节点失败处理，绝不留悬导致了悬挂会话）；
 * ② 失败后进入退避窗口，窗口内 obtain 快速失败——否则每个工具节点都要撞一次 connectTimeout，
 * Executor 会被拖成串行慢查询；③ 无会话时 discard 是 no-op 且不误锁退避。
 */
class McpBrokerClientProviderTest {

    /** :1 = 立即 connection refused（无等待），backoff 给足以免测试受调度抖动影响 */
    private McpBrokerClientProvider provider() {
        return new McpBrokerClientProvider("http://127.0.0.1:1/sse", Duration.ofSeconds(2), 3_000L);
    }

    @Test
    @DisplayName("握手失败：抛错并进入退避，窗口内 obtain 快速失败")
    void backoffAfterFailedHandshake() {
        McpBrokerClientProvider provider = provider();

        assertThatThrownBy(provider::obtain)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mcp 经纪人握手失败");

        assertThatThrownBy(provider::obtain)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("退避剩余");
    }

    @Test
    @DisplayName("无会话时 discard 不抛且不误锁")
    void discardWhenNoSessionIsNoop() {
        McpBrokerClientProvider provider = provider();
        provider.discard();

        // 未被退避窗口挡住：仍然走到真实建连（失败即证明没被误锁）
        assertThatThrownBy(provider::obtain)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mcp 经纪人握手失败");
    }
}
