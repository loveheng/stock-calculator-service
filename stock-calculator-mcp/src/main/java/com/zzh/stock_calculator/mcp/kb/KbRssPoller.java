package com.zzh.stock_calculator.mcp.kb;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * RSS 订阅源轮询（mcp-blogger-kb M1b）：默认 6h 一轮（启动 1min 后首拉），逐源 fail-open。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "kb.rss.poll-enabled", havingValue = "true", matchIfMissing = true)
public class KbRssPoller {

    private final KbSourceService sourceService;

    @Scheduled(fixedDelayString = "${kb.rss.poll-fixed-delay-ms:21600000}",
            initialDelayString = "${kb.rss.poll-initial-delay-ms:60000}")
    public void poll() {
        List<Map<String, Object>> results = sourceService.pollAllRss();
        log.info("RSS 轮询完成: {} 个源", results.size());
    }
}
