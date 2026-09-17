package com.zzh.stockcalc.contract.message;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 记忆差量提炼任务（docs/copilot/memory-profile.md §五提炼链）：
 * main tick 闸门通过后组装差量（水位后 ok 片段 + 当前窗口记忆快照）下发，
 * data MemoryExtractWorker 提炼后经 result.memory.extracted 上行。
 * userId/sessionId/processedUpToMessageId 为入库上下文，result 原样回传（data 零 DB，关联靠 payload）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryExtractTask {

    /** 用户 ID（对齐 ai_chat_session.user_id 口径） */
    private String userId;

    /** 会话窗口 ID（记忆跟随窗口，upsert 目标 + 水位归属） */
    private Long sessionId;

    /** 已处理到的消息ID水位（= 差量内最大 ok 消息 id；result 成功 main 才推进） */
    private Long processedUpToMessageId;

    /** 当前窗口记忆快照（供冲突改写参考，避免重复） */
    private List<MemoryEntry> currentMemories;

    /** 对话片段（user + assistant 成对，时间正序，片段截断带「[已截断]」尾标） */
    private List<ConversationMessage> conversation;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemoryEntry {

        private String topic;
        private String content;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConversationMessage {

        /** ai_chat_message.id（LLM 溯源引用；result.sourceMessageIds 校验锚点） */
        private Long messageId;

        private String role; // "user" or "assistant"
        private String content;
    }
}
