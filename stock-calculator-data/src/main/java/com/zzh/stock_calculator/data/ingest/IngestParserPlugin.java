package com.zzh.stock_calculator.data.ingest;

import com.zzh.stockcalc.contract.message.ArticleIngestedPayload;
import tools.jackson.databind.JsonNode;

/**
 * parser 插件 SPI（阶段 5 扩展规范）：每个新数据源实现本接口并注册为 data 模块
 * @Component，source() 即 URL 路径 {source} 与契约 ArticleIngestedPayload.source。
 * 新源接入只改 data + contract，主服务零改动（§8 阶段 5 验收口径）。
 * 实现约定：externalId/content 缺失等载荷非法抛 IllegalArgumentException（→ 400）；
 * articleId 用 IngestArticleIds.of 生成（幂等锚点，禁止随机 id）。
 */
public interface IngestParserPlugin {

    /** 数据源标识（路径注册键，如 "generic"） */
    String source();

    /** 原始 JSON 载荷 → 标准化文章 */
    ArticleIngestedPayload parse(JsonNode payload);
}
