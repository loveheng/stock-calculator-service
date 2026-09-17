package com.zzh.stockcalc.contract.message;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 记忆差量提炼结果（docs/copilot/memory-profile.md §五提炼链）：
 * 窗口记忆 upsert + 水位推进清锁。userId/sessionId 由 task 回传（data 零 DB，无信封关联）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryExtractedResult {

    private String userId;

    private Long sessionId;

    /** 处理到的消息ID水位（task 原样回传；main 仅前进推进） */
    private Long processedUpToMessageId;

    /** 提炼的记忆条目 */
    private List<MemoryEntry> memories;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemoryEntry {

        private String topic;

        private String content;

        /** 记录类型标签（§六六类，LLM 分类；仅作高价值触发信号，不落库——决策 #14）：
         *  choice / tradeoff / taboo / reply_preference / habit_preference / goal_stage */
        private List<String> recordTypes;

        /** 来源 ai_chat_message.id（仅 user 消息）；main 校验后入库，最多保留 20 条 */
        private List<Long> sourceMessageIds;
    }
}
