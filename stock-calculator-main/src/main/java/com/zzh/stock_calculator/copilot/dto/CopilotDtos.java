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
}
