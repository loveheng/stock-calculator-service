package com.zzh.stock_calculator.copilot.controller;

import com.zzh.stock_calculator.common.ApiResponse;
import com.zzh.stock_calculator.copilot.dto.CopilotDtos.AskRequest;
import com.zzh.stock_calculator.copilot.dto.CopilotDtos.AskResponse;
import com.zzh.stock_calculator.copilot.dto.CopilotDtos.MessageItem;
import com.zzh.stock_calculator.copilot.dto.CopilotDtos.ThreadPageResponse;
import com.zzh.stock_calculator.copilot.entity.AiChatMessage;
import com.zzh.stock_calculator.copilot.repository.AiChatMessageRepository;
import com.zzh.stock_calculator.copilot.repository.AiChatSessionRepository;
import com.zzh.stock_calculator.copilot.service.AiChatOrchestrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Copilot AI 聊天控制层（3 端点）。
 * POST /threads/{scopeId}/messages - 发送提问
 * GET  /threads/{scopeId}/messages?before=&limit=20 - 获取消息列表
 * DELETE /threads/{scopeId} - 级联清理会话
 */
@Slf4j
@RestController
@RequestMapping("/api/copilot")
@RequiredArgsConstructor
public class CopilotController {

    private final AiChatOrchestrationService orchestrationService;
    private final AiChatSessionRepository sessionRepository;
    private final AiChatMessageRepository messageRepository;

    // ==================== POST /threads/{scopeId}/messages ====================

    /** 用户发起 AI 提问 */
    @PostMapping("/threads/{scopeId}/messages")
    public ApiResponse<AskResponse> sendMessage(@PathVariable("scopeId") String scopeId,
                                                 @RequestAttribute("authUserId") String userId,
                                                 @RequestBody AskRequest req) {
        try {
            AskResponse resp = orchestrationService.ask(userId, scopeId, req);
            return ApiResponse.success(resp);
        } catch (IllegalArgumentException e) {
            log.warn("Bad Request: {}", e.getMessage());
            return ApiResponse.fail(400, e.getMessage());
        } catch (com.zzh.stock_calculator.common.BusinessException e) {
            log.warn("Business error: code={}, msg={}", e.getCode(), e.getMessage());
            return ApiResponse.fail(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error: ", e);
            return ApiResponse.fail(500, "服务器内部错误");
        }
    }

    // ==================== GET /threads/{scopeId}/messages ====================

    /** 获取指定 scope 的聊天消息（支持 keyset 翻页） */
    @GetMapping("/threads/{scopeId}/messages")
    public ApiResponse<ThreadPageResponse> getMessages(@PathVariable("scopeId") String scopeId,
                                                        @RequestParam(required = false) Long before,
                                                        @RequestParam(defaultValue = "20") int limit,
                                                        @RequestAttribute("authUserId") String userId) {
        try {
            var sessionOpt = sessionRepository.findActiveByUserIdAndScopeId(userId, scopeId);
            if (sessionOpt.isEmpty()) {
                return ApiResponse.success(ThreadPageResponse.builder()
                        .sessionId(0L).scopeId(scopeId).title("")
                        .messages(new ArrayList<>()).hasMore(false).oldestId(null).build());
            }
            var session = sessionOpt.get();
            List<AiChatMessage> msgs;
            if (before != null && before > 0) {
                msgs = messageRepository.findBeforeKeyset(session.getId(), before, limit);
            } else {
                // 首次拉取：最近 N 条（排除已软删）
                msgs = messageRepository.findRecentActiveBySessionId(session.getId());
            }
            List<MessageItem> items = msgs.stream()
                    .map(this::toMessageItem)
                    .toList();
            boolean hasMore = msgs.size() >= limit;
            Long oldestId = msgs.isEmpty() ? null : msgs.get(msgs.size() - 1).getId();
            return ApiResponse.success(ThreadPageResponse.builder()
                    .sessionId(session.getId())
                    .scopeId(scopeId)
                    .title(session.getTitle())
                    .messages(items)
                    .hasMore(hasMore)
                    .oldestId(oldestId)
                    .build());
        } catch (Exception e) {
            log.error("Failed to fetch messages: ", e);
            return ApiResponse.fail(500, "获取消息失败: " + e.getMessage());
        }
    }

    // ==================== DELETE /threads/{scopeId} ====================

    /** 级联软删除指定 scope 的所有 Copilot 数据 */
    @DeleteMapping("/threads/{scopeId}")
    public ApiResponse<Void> deleteThread(@PathVariable("scopeId") String scopeId,
                                          @RequestAttribute("authUserId") String userId) {
        try {
            orchestrationService.cascadeDeleteByScopeId(userId, scopeId);
            return ApiResponse.success(null);
        } catch (Exception e) {
            log.error("Failed to delete thread: ", e);
            return ApiResponse.fail(500, "删除失败: " + e.getMessage());
        }
    }

    // ==================== Helpers ====================

    /**
     * CopilotController（v1.5.1 修复：移除 Spring Security 依赖）。
     * <p>鉴权复用项目自定义拦截器 AuthInterceptor —— 成功时自动注入
     * {@code @RequestAttribute("authUserId") String} / {@code @RequestAttribute("authTokenHash") String}。</p>
     */
    private MessageItem toMessageItem(AiChatMessage m) {
        return MessageItem.builder()
                .id(m.getId())
                .role(m.getRole())
                .content(m.getContent())
                .contextOverview(m.getContextOverview())
                .timeAnchor(m.getTimeAnchor())
                .clientMessageId(m.getClientMessageId())
                .ctime(m.getCtime())
                .build();
    }
}
