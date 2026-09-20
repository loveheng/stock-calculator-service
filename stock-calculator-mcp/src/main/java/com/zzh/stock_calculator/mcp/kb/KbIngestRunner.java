package com.zzh.stock_calculator.mcp.kb;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 离线灌书入口（kb.ingest.enabled=true 才装配，默认关）：启动即灌，
 * 完成后日志提示可 Ctrl-C。一次性动作不做成常驻任务（design.md 决策）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "kb.ingest", name = "enabled", havingValue = "true")
public class KbIngestRunner implements CommandLineRunner {

    private final KbIngestService ingestService;
    private final KbIngestProperties properties;

    @Override
    public void run(String... args) {
        if (properties.getPath() == null || properties.getPath().isBlank()
                || properties.getTitle() == null || properties.getTitle().isBlank()) {
            throw new IllegalStateException("kb.ingest.enabled=true 但 path/title 未配置");
        }
        log.info("离线灌书开始: {} -> {}", properties.getTitle(), properties.getPath());
        var result = ingestService.ingest(Path.of(properties.getPath()), properties.getTitle(),
                properties.getAuthor(), properties.getCategory(), properties.getReadingOrder());
        log.info("灌书完成: {}，可 Ctrl-C 退出（result={}）", properties.getTitle(), result);
    }
}
