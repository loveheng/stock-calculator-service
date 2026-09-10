package com.zzh.stock_calculator.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 搜索端点 DTO（api 文档 §2/§3/§4/§5；SSE 事件 DTO 同文件）。
 * dateRange 用 String 承接：手动解析以稳定返回 400「日期范围无效」，
 * 避免反序列化失败落进 500 兜底。
 */
public class SearchDtos {

    // ==================== §2 公告检索 ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AnnouncementSearchRequest {
        private String query;
        /** 6 位数字码集合；提供时为硬过滤，缺省 = 不限股票 */
        private List<String> stockCodes;
        private DateRangeParam dateRange;
        /** 缺省 10，上限 50 */
        private Integer topK;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AnnouncementSearchResponse {
        /** 一期口径 = items.size()（topK 截断后返回条数） */
        private int total;
        private List<AnnouncementItem> items;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AnnouncementItem {
        /** announcementId（CNINFO annId） */
        private String resultId;
        private String stockId;
        private String stockName;
        /** yyyy-MM-dd */
        private String annDate;
        private String title;
        private String summary;
        private String sourceUrl;
    }

    // ==================== §3 CLS 检索 ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ClsSearchRequest {
        private String query;
        private DateRangeParam dateRange;
        private Integer topK;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ClsSearchResponse {
        private int total;
        private List<ClsItem> items;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ClsItem {
        /** cls_article.id 文本（C4 口径，勿合成编号） */
        private String resultId;
        /** yyyy-MM-dd HH:mm，东八区 */
        private String publishedAt;
        /** 恒 telegraph（Q3 终版定案：无早报/晚报，语料即财联社电报） */
        private String edition;
        private String title;
        /** brief 优先，缺失截断 content ≤200 字 */
        private String summary;
        private List<Mention> mentions;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Mention {
        private String stockId;
        private String stockName;
    }

    // ==================== §4 综合摘要 ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CompositeRequest {
        private String query;
        private List<String> stockCodes;
        private DateRangeParam dateRange;
    }

    /** JSON 降级信封 data（方案 B，内容协商回落时使用） */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CompositeResponse {
        private String summary;
        private List<Citation> citations;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Citation {
        /** announcement / cls */
        private String kind;
        private String resultId;
        private String stockId;
        /** yyyy-MM-dd（cls 取发布日期） */
        private String date;
        private String title;
    }

    // ==================== §5 档案卡 ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StockProfileResponse {
        private String stockId;
        private String stockName;
        private List<LatestAnnouncement> latestAnnouncements;
        /** P2 数据，一期恒 null（前端隐藏区块） */
        private ClsMention clsMention;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class LatestAnnouncement {
        private String annId;
        private String annDate;
        private String title;
        private String summary;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ClsMention {
        private long count7d;
        private List<ClsMentionItem> items;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ClsMentionItem {
        private String publishedAt;
        private String summary;
    }

    // ==================== 公共 ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DateRangeParam {
        private String start;
        private String end;
    }

    // ==================== SSE 事件（§4 方案 A） ====================

    /** event: meta —— citations 先于 delta 发出，保证引用先上屏 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MetaEvent {
        private List<Citation> citations;
    }

    /** event: delta —— 渐进追加文本 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DeltaEvent {
        private String text;
    }

    /** event: done —— code 200，随后 complete 关流 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DoneEvent {
        private int code;
        private String message;
    }

    /** event: error —— 流中异常（阶段一失败走控制器 JSON 回落，不经此事件） */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SseErrorEvent {
        private int code;
        private String message;
        private Long retryAfterSeconds;
    }
}
