package com.zzh.stockcalc.contract.message;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户画像重抽任务（docs/copilot/memory-profile.md §五画像链）：
 * main 变化驱动触发（ΔCount ≥ 阈值或高价值类型变动）组装加权条目下发，
 * data MemoryProfileWorker 全量重抽后经 result.memory.profile 上行。
 * userId 由 result 回传（data 零 DB，关联靠 payload）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryProfileTask {

    /** 用户 ID（画像入库归属） */
    private String userId;

    /** 全局 active 记忆条数（稀疏冷启动护栏判断依据，§六） */
    private Integer totalActiveMemories;

    /** 快照 max(updated_at)（epoch 毫秒；result 回传后 main 仅 GREATEST 前进推进游标，决策 #19） */
    private Long snapshotMaxUpdatedAt;

    /** 黑名单特征（用户手动移除，严禁再输出，决策 #21） */
    private List<String> blacklistedFeatures;

    /** 加权记忆条目（per-topic Top-M + topic 内位次衰减，决策 #17） */
    private List<WeightedMemoryEntry> entries;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WeightedMemoryEntry {

        private String topic;
        private String content;

        /** topic 内位次线性衰减权重（最新 1.0；冲突时以最高 weight 为准，§六） */
        private double weight;

        /** 相对时间距离标签（如「最新（今日）/ 3天前」） */
        private String relativeDistance;
    }
}
