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
        /** 任务类型（custom-stats-api.md §2.1）：custom_stat = 自定义统计代码生成，路由专用提示词模版；
         *  缺省/未知值 = 现有聊天模版，行为零变化。仅参与模版路由编排，不落库、不打日志 */
        private String taskType;
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
        /** 结构化动作（从 LLM 输出的动作块容错提取，ephemeral：不落库不打日志）；无块/解析失败 = null。
         *  前端按白名单 + 形状守卫消费（utils/copilotActions 纪律），后端不校验 payload 内容 */
        private java.util.List<CopilotActionItem> actions;
    }

    /** 结构化动作条目（透传形状：type + 原始 payload）；payload 保持原始 JSON 结构，后端不做语义校验 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CopilotActionItem {
        private String type;
        private Object payload;
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
