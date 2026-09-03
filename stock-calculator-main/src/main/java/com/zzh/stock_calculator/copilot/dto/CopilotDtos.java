package com.zzh.stock_calculator.copilot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Copilot API 数据传输对象。
 */
public final class CopilotDtos {

    private CopilotDtos() {}

    // ==================== Request / Response ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AskRequest {
        private String question;
        private String sessionTitle;
        private String clientMessageId;
        private String contextSummary;   // ephemeral JSON（不落库）
        private String contextOverview;  // 落库标量 JSON
        private String timeAnchor;       // 时间截面标记
        /** 区块级聚焦 ID（如 home:short_term）：仅参与 Prompt 模版路由编排，不落库、不打日志；缺省 = 整页口径 */
        private String focusBlockId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AskResponse {
        private Long userMessageId;
        private Long assistantMessageId;
        private String content;
        private Integer promptTokens;
        private Integer completionTokens;
        private String channel;
        private String userContextOverview;
        private String userTimeAnchor;
        private Long ctime;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageItem {
        private Long id;
        private String role;
        private String content;
        private String contextOverview;
        private String timeAnchor;
        private String clientMessageId;
        private Long ctime;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ThreadPageResponse {
        private Long sessionId;
        private String scopeId;
        private String title;
        private java.util.List<MessageItem> messages;
        private Boolean hasMore;
        private Long oldestId;
    }

    // ==================== SSE 流式事件负载 ====================

    /** delta 事件负载：{"text":"<增量token>"} */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DeltaEvent {
        private String text;
    }

    /** error 事件负载：LLM 流中异常，前端按可重发处理（userMsg 已标 failed，走续跑分支） */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorEvent {
        private int code;
        private String subCode;
        private String message;
    }

    // ==================== Prompt 模版在线管理 ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PromptTemplateItem {
        private Long id;
        private String tag;
        private String content;
        private Long ctime;
        private Long mtime;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PromptUpsertRequest {
        private String content;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PromptTemplateHistoryItem {
        private Long id;
        private String tag;
        private Integer rev;
        private String content;
        private String operation;
        private Long ctime;
    }
}
