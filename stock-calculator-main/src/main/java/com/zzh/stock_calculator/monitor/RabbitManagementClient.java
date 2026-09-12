package com.zzh.stock_calculator.monitor;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * LavinMQ/RabbitMQ 管理 API 客户端（R4）：一次拉取 vhost 全部队列的
 * 深度与 consumer 数（深度=积压先行指标，consumer 数=数据服务存活探针）。
 * LavinMQ 管理 API 与 RabbitMQ 兼容（GET /api/queues/{vhost}）；
 * 查询失败原样抛出——巡检调用方将其转化为 BROKER_UNREACHABLE 告警（fail-loud）。
 */
@Component
@RequiredArgsConstructor
public class RabbitManagementClient {

    private final RestClient pipelineMgmtRestClient;
    private final PipelineWatchProperties properties;
    private final ObjectMapper objectMapper;

    /** 队列运行时状态快照 */
    public record QueueStat(String name, int messages, int consumers) {}

    /** 拉取 vhost 内全部队列统计 */
    public List<QueueStat> fetchQueueStats() {
        String path = "/api/queues/" + encodeVhost(properties.getVhost());
        // uri(URI) 重载不重编码：String 模板会把已转义的 %2F 再编码成 %252F，
        // LavinMQ 按 404 "Vhost %2F does not exist" 拒绝（巡检永远误报 BROKER_UNREACHABLE）
        String body = pipelineMgmtRestClient.get()
                .uri(URI.create(properties.getMgmtBaseUrl() + path))
                .headers(h -> h.set("Authorization", basicAuth()))
                .retrieve()
                .body(String.class);
        List<QueueStat> stats = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return stats;
        }
        List<?> rows = objectMapper.readValue(body, List.class);
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            String name = String.valueOf(map.get("name"));
            int messages = numberOf(map.get("messages"));
            int consumers = numberOf(map.get("consumers"));
            stats.add(new QueueStat(name, messages, consumers));
        }
        return stats;
    }

    /** vhost 入路径转义：/ → %2F（管理 API 约定） */
    private static String encodeVhost(String vhost) {
        return vhost == null ? "%2F" : vhost.replace("/", "%2F");
    }

    private String basicAuth() {
        String token = properties.getMgmtUsername() + ":" + properties.getMgmtPassword();
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    private static int numberOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
