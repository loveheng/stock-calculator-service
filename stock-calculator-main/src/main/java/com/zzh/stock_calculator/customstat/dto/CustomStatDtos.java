package com.zzh.stock_calculator.customstat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 自定义统计持久化 DTO（D17 契约：docs/custom-stats-server-sync.md §4）。
 *
 * <p>上行 payload 即前端 {@code CustomStatDefinition} 完整 JSON——服务端哑存储：
 * 不映射为具名字段类（防未来字段/版本前向兼容丢数据），仅解析校验后原样落 JSONB。</p>
 */
public final class CustomStatDtos {

    private CustomStatDtos() {}

    /** GET /api/custom-stats 响应 data：全量定义（payload 原样解析回传，按 updated_at_client 倒序）；空库 = 空数组非 404 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CustomStatListResponse {
        private List<Object> list;
    }
}
