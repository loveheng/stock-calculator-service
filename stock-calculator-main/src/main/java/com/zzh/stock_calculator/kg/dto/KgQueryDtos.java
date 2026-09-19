package com.zzh.stock_calculator.kg.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * kg 查询端点 DTO（时间轴/实体检索，docs/ai-pipeline/cls-news-kg.md 二期前端可视化）。
 * EventDayAgg / EntitySuggestView / RelatedEntityView 为原生 SQL 投影接口
 * （列别名与 getter 对应，供 Spring Data 接口投影反射取值），其余为响应载体。
 */
public class KgQueryDtos {

    // ==================== 原生查询投影 ====================

    /** 日聚合投影：kg_event 按 article_id 分组（日头 = 一篇汇编稿） */
    public interface EventDayAgg {
        Long getArticleId();

        long getEventCount();

        java.time.OffsetDateTime getDayTime();
    }

    /** 实体检索投影（建议/命中实体共用，轻量字段） */
    public interface EntitySuggestView {
        Long getId();

        String getName();

        String getEntityType();

        String getAnchorType();

        int getMentionCount();
    }

    /** 关联实体投影（同事件共现实体计数） */
    public interface RelatedEntityView {
        Long getId();

        String getName();

        String getEntityType();

        String getAnchorType();

        long getCoMentionCount();
    }

    // ==================== 时间轴 ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TimelineResponse {
        /** 日组列表（按组内最新事件时间倒序；空态 = 空数组） */
        private List<DayGroup> days;
        private int page;
        private int pageSize;
        /** 命中条件的事件日总数（分页控制依据） */
        private long totalDays;
        private boolean hasMore;
        /** keyword 命中的实体（top3，供前端置顶实体摘要卡；无 keyword 时为空数组） */
        private List<EntitySuggest> matchedEntities;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DayGroup {
        private Long articleId;
        /** 日头日期（汇编稿 ctime 按本地时区取日期，yyyy-MM-dd） */
        private String date;
        /** 汇编稿原标题（自带「x月x日」但无年份，作副标题展示） */
        private String articleTitle;
        private long eventCount;
        /** 组内事件（按事件时间升序、时间空者沉底、再按 id 升序 ≈ 汇编原文阅读序） */
        private List<EventCard> events;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EventCard {
        private Long id;
        /** 归一化事件日期（yyyy-MM-dd，取自 event_time；解析失败为 null，用 timeText 兜底） */
        private String eventDate;
        private String eventTimeText;
        private String title;
        private String detail;
        private String eventType;
        /** 溯源外键（前端跳原文详情） */
        private Long articleId;
        private List<EntityChip> entities;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EntityChip {
        private Long id;
        private String name;
        private String entityType;
        private String anchorType;
    }

    // ==================== 实体检索 ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EntitySuggest {
        private Long id;
        private String name;
        private String entityType;
        private String anchorType;
        private int mentionCount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EntityDetailResponse {
        private Long id;
        private String name;
        private String entityType;
        private String anchorType;
        private String anchorId;
        private List<String> aliases;
        private int mentionCount;
        /** 参与事件数（kg_event_entity 口径） */
        private long eventCount;
        private String firstSeenAt;
        private String lastSeenAt;
        /** 高频共现实体（同事件共现计数倒序 top8，供「关联实体」chips 漫游） */
        private List<RelatedEntity> relatedEntities;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RelatedEntity {
        private Long id;
        private String name;
        private String entityType;
        private String anchorType;
        private long coMentionCount;
    }
}
