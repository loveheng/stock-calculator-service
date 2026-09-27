package com.zzh.stock_calculator.common;

import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP 会话托管守卫（2026-09-27 启动解耦）：main 曾因自动配置在 bean 创建期 initialize()
 * 而把 :18083 变成启动期硬依赖（20s 超时 → BeanCreationException → Application run failed）。
 * 本类锁住改造后的三条语义：握手幂等、失效后可重握、未装配时报业务错（不再拖垮进程）。
 */
class McpSessionManagerTest {

    private final McpSyncClient client = Mockito.mock(McpSyncClient.class);

    @Test
    @DisplayName("握手幂等：多次取值只 initialize 一次")
    void handshakeOnce() {
        McpSessionManager manager = new McpSessionManager(List.of(this.client));

        assertThat(manager.client()).isSameAs(this.client);
        manager.ensureReady();
        assertThat(manager.client()).isSameAs(this.client);

        Mockito.verify(this.client, Mockito.times(1)).initialize();
    }

    @Test
    @DisplayName("invalidate 后重新握手")
    void reHandshakeAfterInvalidate() {
        McpSessionManager manager = new McpSessionManager(List.of(this.client));

        assertThat(manager.client()).isSameAs(this.client);
        manager.invalidate();
        assertThat(manager.client()).isSameAs(this.client);

        Mockito.verify(this.client, Mockito.times(2)).initialize();
    }

    @Test
    @DisplayName("未装配 client：报业务异常（不阻塞启动）")
    void noClientFailsOnlyAtCallTime() {
        McpSessionManager manager = new McpSessionManager(List.of());

        assertThat(manager.isEnabled()).isFalse();
        assertThatThrownBy(manager::client)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MCP client 未装配");
        // 未装配时 ensureReady/warmUp 都应是 no-op（不走持有 client 的路径）
        manager.ensureReady();
        manager.warmUp();
    }
}
