package com.zzh.stock_calculator.guide.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 选股引导端点 DTO（docs/guide/design.md §四；SSE 无流式，纯阻塞 JSON）。
 * 字段声明序即 Jackson 序列化序——nextStep/nextSteps 必须保持首位
 * （实施红线②：keep_head(2KB) 截断安全位 + LLM 第一眼看到流程指令）。
 */
public class GuideDtos {

    // ==================== Step 1 消息→候选股 ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AnalyzeMessageRequest {
        /** 用户听到的消息原文（口语转述即可，≤500 字） */
        private String message;
        /** 候选依据与提及计数的时间窗（天），缺省 7，范围 1-30 */
        private Integer days;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AnalyzeMessageResponse {
        /** 流程指令（首位红线②）：告诉调用方下一步该做什么 */
        private String nextStep;
        /** 候选股票清单（≤8，STOCK 直锚在前，题材扩展按提及数降序） */
        private List<Candidate> candidates;
        /** 消息锚定结果回显（含未锚定自由词，anchorType=null） */
        private List<EntityHit> entities;
        /** 澄清素材关键词（LLM 抽取的未锚定词；空候选时用于追问用户） */
        private List<String> keywords;
        /** 空候选兜底：按关键词检索到的相关电报（≤5 条） */
        private List<ArticleBrief> relatedArticles;
        /** true = LLM 抽取链路降级（结果仅来自词典快路径，可提示用户） */
        private boolean llmDegraded;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Candidate {
        private String stockId;
        private String stockName;
        /** STOCK = 消息直接锚定个股；SUBJECT = 经题材两跳扩展 */
        private String hitType;
        /** 命中来源名（STOCK=公司名；SUBJECT=题材名） */
        private String hitName;
        /** 近窗口被电报提及的文章数（热度依据） */
        private long recentMentionCount;
        /** 依据样例文章头（≤2 条，仅 title+ctime，红线②） */
        private List<ArticleBrief> sampleArticles;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EntityHit {
        private String name;
        /** STOCK / CLS_SUBJECT；null = 未锚定自由词（已按不编造原则丢弃，不入候选） */
        private String anchorType;
        private String anchorId;
    }

    // ==================== Step 2 个股引导档案 ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StockBriefResponse {
        /** 动作建议（首位红线②）：技术面核实/设提醒等下一步指引 */
        private List<String> nextSteps;
        private String stockId;
        private String stockName;
        /** 近窗口电报提及（count + 近几条文章头） */
        private MentionBlock clsMention;
        /** 近窗口题材归属标签（articleCount 降序） */
        private List<SubjectItem> subjects;
        /** 近期公告蒸馏摘要（≤3 条，annDate 倒序） */
        private List<AnnouncementItem> announcements;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MentionBlock {
        private long count;
        private List<ArticleBrief> articles;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SubjectItem {
        private Long subjectId;
        private String subjectName;
        private long articleCount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AnnouncementItem {
        private String annDate;
        private String title;
        private String summary;
    }

    // ==================== 公共 ====================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ArticleBrief {
        private Long articleId;
        private String title;
        /** epoch 秒（cls_article.time 口径） */
        private Long ctime;
    }
}
