package com.zzh.stock_calculator.data.ingest;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * webhook ingest 配置（阶段 5）：默认关闭，部署经 DATASVC_INGEST_ENABLED=true +
 * INGEST_SECRET 开启；secret 为 HMAC-SHA256 共享密钥（与推送方约定），空值时端点 503。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "datasvc.ingest")
public class IngestProperties {

    private boolean enabled = false;

    private String secret = "";

    /** 时间戳防重放窗口（秒） */
    private int skewSeconds = 300;
}
