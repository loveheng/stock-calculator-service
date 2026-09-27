package com.zzh.stock_calculator.common;

import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MCP 会话托管（2026-09-27 启动解耦）：入口在
 * {@code spring.ai.mcp.client.initialized=false}——自动配置只造 client 不握手，握手改由本类
 * 在「首次使用」或「启动后异步预热」时做，于是 main 不再被 orchestration(:18083 dispatch)
 * 的启动顺序绑架：对端没起时 main 照常起来，只是相关功能报错，而不是进程启动失败。
 *
 * <p>历史行为（现在要治的病）：自动配置 {@code McpClientAutoConfiguration.mcpSyncClients()}
 * 在 bean 创建期直接 initialize()，20s 没等到 SSE 响应 → BeanCreationException →
 * Application run failed（依赖链 asyncTaskResultConsumer → asyncTaskChannelService → mcpSyncClients）。</p>
 *
 * <p>两条路径都覆盖到：① 使用点显式 {@link #client()}（幂等确保握手）；② 走
 * ToolCallbackProvider 的 LLM 工具调用没法插回调（框架内部直接 callTool），靠
 * {@link #warmUp()} 在 ApplicationReadyEvent 后异步握手兜底——两者共用同一个 client 对象。</p>
 *
 * <p>会话失效（对端重启）时调用方 {@link #invalidate()}，下次取值重新 initialize；
 * 注意与 orchestration 侧不同：这里仍复用 starter 的单例 client，不做 transport 重建。</p>
 */
@Slf4j
@Component
public class McpSessionManager {

    /** 预热重试上限（间隔固定 5s）：约 1 分钟内可等来对端就绪，超过转由调用点时握手 */
    private static final int WARMUP_MAX_ATTEMPTS = 12;
    private static final long WARMUP_INTERVAL_MS = 5_000L;

    private final List<McpSyncClient> clients;
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final ReentrantLock lock = new ReentrantLock();

    public McpSessionManager(List<McpSyncClient> clients) {
        this.clients = clients;
    }

    /** MCP client 是否可用（未装配或握手尚未成功都算不可用） */
    public boolean isEnabled() {
        return !this.clients.isEmpty();
    }

    /**
     * 取已握手的会话；未装配抛异常，握手失败透出原始异常（调用方按业务失败处理，
     * 绝不把这做成启动期阻塞）。
     */
    public McpSyncClient client() {
        ensureReady();
        return this.clients.stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("MCP client 未装配（orchestration dispatch 不可达）"));
    }

    /** 幂等握手：成功后缓存标记，失败不设标记（下次可重试） */
    public void ensureReady() {
        if (this.ready.get() || this.clients.isEmpty()) {
            return;
        }
        this.lock.lock();
        try {
            if (this.ready.get()) {
                return;
            }
            long start = System.currentTimeMillis();
            this.clients.stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("MCP client 未装配（orchestration dispatch 不可达）"))
                    .initialize();
            this.ready.set(true);
            log.info("[mcp-session] dispatch 会话握手完成 cost={}ms", System.currentTimeMillis() - start);
        } finally {
            this.lock.unlock();
        }
    }

    /** 会话失效后重置：下次 client()/ensureReady() 重新握手 */
    public void invalidate() {
        if (this.ready.getAndSet(false)) {
            log.warn("[mcp-session] dispatch 会话已失效，下次调用重新握手");
        }
    }

    /**
     * 启动后异步预热：不阻塞启动。失败按退避重试若干次，仍失败就交给调用点时按需握手
     * （那时会明确报业务错，而不是把进程拖死）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (this.clients.isEmpty()) {
            log.info("[mcp-session] MCP client 未装配（MCP_CLIENT_ENABLED=false），跳过会话预热");
            return;
        }
        Thread.ofVirtual().name("mcp-session-warmup").start(() -> {
            for (int attempt = 1; attempt <= WARMUP_MAX_ATTEMPTS; attempt++) {
                try {
                    ensureReady();
                    return;
                } catch (RuntimeException e) {
                    log.warn("[mcp-session] dispatch 会话预热失败 {}/{}: {}",
                            attempt, WARMUP_MAX_ATTEMPTS, e.getMessage());
                    sleepQuietly(WARMUP_INTERVAL_MS);
                }
            }
            log.error("[mcp-session] dispatch 会话预热 {} 次均未成功，改由调用时按需握手",
                    WARMUP_MAX_ATTEMPTS);
        });
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
