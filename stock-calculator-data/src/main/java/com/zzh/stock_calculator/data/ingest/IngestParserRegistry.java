package com.zzh.stock_calculator.data.ingest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 插件注册表：source → parser；重复 source 直接启动失败（防接入漂移）。
 */
@Component
@ConditionalOnProperty(prefix = "datasvc.ingest", name = "enabled", havingValue = "true")
public class IngestParserRegistry {

    private final Map<String, IngestParserPlugin> parsers;

    public IngestParserRegistry(List<IngestParserPlugin> plugins) {
        this.parsers = plugins.stream().collect(Collectors.toUnmodifiableMap(
                IngestParserPlugin::source, Function.identity()));
    }

    public Optional<IngestParserPlugin> find(String source) {
        return Optional.ofNullable(parsers.get(source));
    }
}
