package com.zzh.stock_calculator.orchestration.tool;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

/**
 * mcp 经纪人会话提供者（2026-09-27 启动解耦档 2）：替代 starter 自动装配的 McpSyncClient
 * （{@code spring.ai.mcp.client.enabled=false} 关闭），把「会话」变成可丢弃重建的资源。
 *
 * <p>为什么不用自动配置：自动配置的 client 是单例且 bean 创建期可选握手，但一旦 SSE 会话
 * 失效（对端重启/中间层掐连接），McpSyncClient 无法复用，只能重建 transport——而重建能力
 * 只有拥有「谁负责创建」的主权时才有，故自托管。同时启动期完全不碰网络：编排服务不被
 * :18081 的启动顺序绑架，mcp 迟到只是 mcp 节点 failed（Executor 既定语义）。</p>
 *
 * <p>三条契约：① {@link #obtain()} 惰性建连 + 握手，成功后缓存复用；② {@link #discard()}
 * 由调用侧在会话级异常时触发，关闭旧会话并登记退避；③ 退避窗口内的 obtain 快速失败，
 * 不对着一个死端点反复握手（每次失败都让您去撞 20s 超时只会拖垮 Executor）。</p>
 *
 * <p>native 注意：这里用的类（HttpClientSseClientTransport / McpClient / McpSyncClient /
 * JacksonMcpJsonMapper）与自动配置同一套，反射元数据由 spring-ai 的
 * {@code META-INF/spring/aot.factories} RuntimeHintsRegistrar 无条件注册，关闭自动配置不丢 hint。</p>
 */
@Slf4j
@Component
public class McpBrokerClientProvider implements DisposableBean {

    /** 退避上限：累积失败不必无限拉长，15s 足够跨过一次容器重启窗口 */
    private static final long BACKOFF_MAX_MS = 15_000L;

    private final String brokerUrl;
    private final Duration requestTimeout;
    private final long backoffBaseMs;

    private final ReentrantLock lock = new ReentrantLock();

    private volatile McpSyncClient current;
    private volatile long nextConnectAtEpochMs = 0L;
    private volatile int consecutiveFailures = 0;

    public McpBrokerClientProvider(
            @Value("${MCP_BROKER_URL:http://localhost:18081/sse}") String brokerUrl,
            @Value("${spring.ai.mcp.client.request-timeout:40s}") Duration requestTimeout,
            @Value("${orchestration.mcp.broker.backoff-ms:1000}") long backoffBaseMs) {
        this.brokerUrl = brokerUrl;
        this.requestTimeout = requestTimeout;
        this.backoffBaseMs = backoffBaseMs;
    }

    /**
     * 取已握手的会话。不可用时抛 IllegalStateException（调用侧按节点失败处理）。
     * 退避窗口内不再尝试建连，避免每次工具调用都去撞一遍超时。
     */
    public McpSyncClient obtain() {
        McpSyncClient cached = this.current;
        if (cached != null) {
            return cached;
        }
        this.lock.lock();
        try {
            if (this.current != null) {
                return this.current;
            }
            long now = System.currentTimeMillis();
            if (now < this.nextConnectAtEpochMs) {
                throw new IllegalStateException("mcp 经纪人会话暂不可用（退避剩余 "
                        + (this.nextConnectAtEpochMs - now) + "ms，连续失败 "
                        + this.consecutiveFailures + " 次，url=" + this.brokerUrl + "）");
            }
            McpSyncClient fresh = connect();
            this.current = fresh;
            this.consecutiveFailures = 0;
            this.nextConnectAtEpochMs = 0L;
            return fresh;
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * 会话级失败后调用：关闭旧会话并登记退避。下一次 {@link #obtain()} 会全新建连，
     * 从而在对端重启后自愈（无需重启编排服务进程）。业务报错（tool isError）不要调用本方法。
     */
    public void discard() {
        McpSyncClient victim;
        long delay;
        this.lock.lock();
        try {
            victim = this.current;
            this.current = null;
            if (victim != null) {
                this.consecutiveFailures++;
                delay = backoffMsLocked();
                this.nextConnectAtEpochMs = System.currentTimeMillis() + delay;
            } else {
                delay = 0L;
            }
        } finally {
            this.lock.unlock();
        }
        closeQuietly(victim);
        log.warn("[orchestration] mcp 经纪人会话已丢弃，{}ms 后允许重建（连续失败 {} 次）",
                delay, this.consecutiveFailures);
    }

    /** 建 SSE transport + client + initialize（唯一的阻塞点，且只发生在工具调用链路上） */
    private McpSyncClient connect() {
        long start = System.currentTimeMillis();
        URI uri = URI.create(this.brokerUrl);
        String baseUri = uri.getScheme() + "://" + uri.getAuthority();
        String sseEndpoint = uri.getPath() == null || uri.getPath().isBlank() ? "/sse" : uri.getPath();
        try {
            HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(baseUri)
                    .sseEndpoint(sseEndpoint)
                    .connectTimeout(this.requestTimeout)
                    // 显式给 JsonMapper：不依赖 SDK 的 ServiceLoader 发现（native 下不可靠）
                    .jsonMapper(new JacksonMcpJsonMapper(JsonMapper.builder().build()))
                    .build();
            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(this.requestTimeout)
                    .build();
            client.initialize();
            log.info("[orchestration] mcp 经纪人握手完成 url={} cost={}ms",
                    this.brokerUrl, System.currentTimeMillis() - start);
            return client;
        } catch (RuntimeException e) {
            this.consecutiveFailures++;
            this.nextConnectAtEpochMs = System.currentTimeMillis() + backoffMsLocked();
            throw new IllegalStateException("mcp 经纪人握手失败 url=" + this.brokerUrl
                    + " cost=" + (System.currentTimeMillis() - start) + "ms: " + e.getMessage(), e);
        }
    }

    private long backoffMsLocked() {
        long shift = Math.min(this.consecutiveFailures - 1, 4);
        return Math.min(this.backoffBaseMs << shift, BACKOFF_MAX_MS);
    }

    private void closeQuietly(McpSyncClient victim) {
        if (victim == null) {
            return;
        }
        try {
            victim.close();
        } catch (RuntimeException e) {
            log.debug("[orchestration] 关闭旧 mcp 会话时异常（忽略）: {}", e.getMessage());
        }
    }

    @Override
    public void destroy() {
        closeQuietly(this.current);
        this.current = null;
    }
}
