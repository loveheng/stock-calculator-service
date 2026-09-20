package com.zzh.stock_calculator.mcp.kb;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 微博备份单条解析产物（一个观点单元）：发布时间 + 正文。
 * 转发条目的「转发自 X：」出处内联保留在正文中（引用内容与博主评论一体入库）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KbEntryDraft {

    private LocalDateTime publishedAt;

    private String content;
}
