package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * task.memory.extract.delay / result.memory.extract.tick 共用的轻量 payload
 * （docs/copilot/memory-profile.md §五）：main 每轮聊天落库后发布种子（仅 userId+sessionId，
 * per-message TTL 默认 60s）；TTL 到期经 delay 队列 DLX 改写（RESULTS 交换机）原样回 main
 * 成为 tick。tick 不携带业务数据——差量由 main 消费时按会话水位重算，空则丢弃（防风暴闸门）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryExtractTickPayload {

    /** 用户 ID（String，对齐 ai_chat_session.user_id 现行口径：auth 主键 UUID 的字符串形态） */
    private String userId;

    /** 会话窗口 ID（记忆跟随窗口，提炼差量按该会话水位计算） */
    private Long sessionId;
}
