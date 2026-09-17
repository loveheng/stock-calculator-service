package com.zzh.stock_calculator.copilot.controller;

import com.zzh.stock_calculator.common.ApiResponse;
import com.zzh.stock_calculator.copilot.entity.CopilotUserProfile;
import com.zzh.stock_calculator.copilot.service.CopilotMemoryService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * Copilot 记忆与画像控制层（docs/copilot/memory-profile.md §八）。
 * userId 一律取自会话鉴权（authUserId），不收客户端传参（§九用户隔离）。
 */
@Slf4j
@RestController
@RequestMapping("/api/copilot")
@RequiredArgsConstructor
public class CopilotMemoryController {

    private final CopilotMemoryService memoryService;

    // ==================== GET /profile：查看当前画像 ====================

    @GetMapping("/profile")
    public ApiResponse<ProfileView> getProfile(
        @RequestAttribute("authUserId") String userId
    ) {
        Optional<CopilotUserProfile> profile = memoryService.findProfile(
            userId
        );
        if (profile.isEmpty()) {
            return ApiResponse.success(ProfileView.empty(userId));
        }
        CopilotUserProfile row = profile.get();
        CopilotUserProfile.ProfileFields p = row.getProfile();
        return ApiResponse.success(
            ProfileView.builder()
                .userId(userId)
                .personality(
                    p == null || p.getPersonality() == null
                        ? List.of()
                        : p.getPersonality()
                )
                .deepPreferences(
                    p == null || p.getDeepPreferences() == null
                        ? List.of()
                        : p.getDeepPreferences()
                )
                .taboos(
                    p == null || p.getTaboos() == null
                        ? List.of()
                        : p.getTaboos()
                )
                .responsePreferences(
                    p == null || p.getResponsePreferences() == null
                        ? List.of()
                        : p.getResponsePreferences()
                )
                .profileVersion(row.getProfileVersion())
                .blacklistedFeatures(row.getBlacklistedFeatures())
                .lastProfileExtractedAt(row.getLastProfileExtractedAt())
                .build()
        );
    }

    // ==================== PATCH /profile：人工修正画像（决策 #21） ====================

    /**
     * 人工修正画像：从指定字段移除特征并写入黑名单，version+1；
     * 不触发重抽——改动即终态，黑名单压制后续重抽复发（防 LLM 幻觉 UX 死锁）。
     */
    @PatchMapping("/profile")
    public ApiResponse<Void> patchProfile(
        @RequestAttribute("authUserId") String userId,
        @RequestBody RemoveFeatureRequest req
    ) {
        if (req == null || req.getField() == null || req.getFeature() == null) {
            return ApiResponse.fail(400, "field 与 feature 必填");
        }
        boolean ok = memoryService.removeProfileFeature(
            userId,
            req.getField(),
            req.getFeature()
        );
        return ok
            ? ApiResponse.success(null)
            : ApiResponse.fail(404, "画像或特征不存在");
    }

    // ==================== GET /memory：查看本人长期记忆条目 ====================

    @GetMapping("/memory")
    public ApiResponse<List<MemoryView>> listMemories(
        @RequestAttribute("authUserId") String userId
    ) {
        List<MemoryView> items = memoryService
            .listActiveMemories(userId)
            .stream()
            .map(m ->
                MemoryView.builder()
                    .id(m.getId())
                    .sessionId(m.getSessionId())
                    .topic(m.getTopic())
                    .content(m.getContent())
                    .pinned(Boolean.TRUE.equals(m.getPinned()))
                    .ctime(m.getCtime())
                    .build()
            )
            .toList();
        return ApiResponse.success(items);
    }

    // ==================== DELETE /memory/{id}：遗忘（archived + 立即重抽画像） ====================

    @DeleteMapping("/memory/{id}")
    public ApiResponse<Void> forgetMemory(
        @RequestAttribute("authUserId") String userId,
        @PathVariable("id") Long id
    ) {
        boolean ok = memoryService.archiveMemory(userId, id);
        return ok
            ? ApiResponse.success(null)
            : ApiResponse.fail(404, "记忆不存在");
    }

    // ==================== PATCH /memory/{id}/pin：置顶 / 取消置顶（上限 10 条） ====================

    @PatchMapping("/memory/{id}/pin")
    public ApiResponse<Void> setPin(
        @RequestAttribute("authUserId") String userId,
        @PathVariable("id") Long id,
        @RequestBody PinRequest req
    ) {
        if (req == null || req.getPinned() == null) {
            return ApiResponse.fail(400, "pinned 必填");
        }
        boolean ok = memoryService.setPinned(userId, id, req.getPinned());
        if (ok) {
            return ApiResponse.success(null);
        }
        // 区分两类失败：不存在 vs 置顶上限——简化为一句提示，前端据 pinned 语义展示
        return ApiResponse.fail(409, "记忆不存在或已达置顶上限（10 条）");
    }

    // ==================== POST /profile/extract：手动发布一次画像任务 ====================

    @PostMapping("/profile/extract")
    public ApiResponse<Void> extractNow(
        @RequestAttribute("authUserId") String userId
    ) {
        memoryService.dispatchProfileTask(userId);
        return ApiResponse.success(null);
    }

    // ==================== DTO ====================

    @Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ProfileView {

        private String userId;
        private List<String> personality;
        private List<String> deepPreferences;
        private List<String> taboos;
        private List<String> responsePreferences;
        private Integer profileVersion;
        private List<String> blacklistedFeatures;
        private OffsetDateTime lastProfileExtractedAt;

        public static ProfileView empty(String userId) {
            return ProfileView.builder()
                .userId(userId)
                .personality(List.of())
                .deepPreferences(List.of())
                .taboos(List.of())
                .responsePreferences(List.of())
                .profileVersion(0)
                .blacklistedFeatures(List.of())
                .build();
        }
    }

    @Data
    public static class RemoveFeatureRequest {

        /** 画像字段：personality / deepPreferences / taboos / responsePreferences */
        private String field;
        /** 要移除的特征原文（黑名单按原文匹配） */
        private String feature;
    }

    @Data
    public static class PinRequest {

        private Boolean pinned;
    }

    @Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class MemoryView {

        private Long id;
        private Long sessionId;
        private String topic;
        private String content;
        private Boolean pinned;
        private Long ctime;
    }
}
