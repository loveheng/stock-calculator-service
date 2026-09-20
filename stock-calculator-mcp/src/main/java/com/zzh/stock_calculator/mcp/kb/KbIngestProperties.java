package com.zzh.stock_calculator.mcp.kb;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 离线灌书参数（kb.ingest.*）：默认关。启用方式：
 * KB_INGEST_ENABLED=true KB_INGEST_PATH=/path/book.epub KB_INGEST_TITLE=书名 ... 启动即灌。
 */
@Data
@ConfigurationProperties(prefix = "kb.ingest")
public class KbIngestProperties {

    private boolean enabled = false;

    private String path;

    private String title;

    private String author = "";

    private String category = "";

    private Integer readingOrder;
}
