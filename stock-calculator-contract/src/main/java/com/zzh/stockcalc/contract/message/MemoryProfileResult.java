package com.zzh.stockcalc.contract.message;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户画像重抽结果（docs/copilot/memory-profile.md §五画像链）：
 * 画像覆盖入库 + version+1 + 游标推进至快照 max(updated_at)。
 * userId/snapshotMaxUpdatedAt 由 task 回传（data 零 DB，关联靠 payload）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryProfileResult {

    private String userId;

    /** 快照 max(updated_at)（epoch 毫秒；main 仅前进推进，绝不 now()，决策 #19） */
    private Long snapshotMaxUpdatedAt;

    /** 四类画像特征（禁 PII；证据不足为空数组） */
    private ProfileFields profile;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProfileFields {

        /** 性格特征：行为风格、态度倾向、处事方式 */
        private List<String> personality;

        /** 底层偏好：核心价值观、长期偏好、重要原则 */
        private List<String> deepPreferences;

        /** 禁忌：明确排斥的底线要求 */
        private List<String> taboos;

        /** 回复偏好：对回答方式的显式要求与修改请求 */
        private List<String> responsePreferences;
    }
}
