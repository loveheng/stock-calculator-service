package com.zzh.stock_calculator.mcp.config;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP 就绪探针（2026-09-27 编排启动时序）：orchestration 的 McpSyncClient 在 bean 创建期
 * 就连 :18081 SSE 并 initialize，20s 超时直接炸启动——「端口在监听」≠「MCP 服务可用」，
 * 仅 curl 探 18081 端口（甚至根路径 404，curl 无 -f 也算成功）会让 orchestration 抢跑失败。
 * 本端点以 ApplicationReadyEvent 为准：上下文刷新完成 + Tomcat 监听 + MCP SSE transport
 * provider 与工具回调均已装配，并返回工具数量（工具面为空=注册未完成，判未就绪）。
 * 消费方：镜像 HEALTHCHECK（curl -fs）/ docker-compose healthcheck / orchestration 启动前等待。
 * <p>与 admin 系端点同为该 anarchic 内部端点：mcp 服务未挂任何过滤链，容器网络内可达。</p>
 */
@RestController
@RequestMapping("/internal")
public class ReadinessController {

    private final MethodToolCallbackProvider toolCallbackProvider;

    private volatile boolean ready = false;

    public ReadinessController(MethodToolCallbackProvider toolCallbackProvider) {
        this.toolCallbackProvider = toolCallbackProvider;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        this.ready = true;
    }

    /** 就绪：200 {"status":"UP","tools":N}；否则 503（HttpStatus 需 -f 生效）。 */
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, Object> body = new LinkedHashMap<>();
        int tools = toolCallbackProvider.getToolCallbacks().length;
        boolean up = ready && tools > 0;
        body.put("status", up ? "UP" : "DOWN");
        body.put("tools", tools);
        body.put("checkedAt", Instant.now().toString());
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
