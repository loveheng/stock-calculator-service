package com.zzh.stock_calculator.notify.controller;

import com.zzh.stock_calculator.common.ApiResponse;
import com.zzh.stock_calculator.notify.entity.PushMessage;
import com.zzh.stock_calculator.notify.repository.PushMessageRepository;
import com.zzh.stock_calculator.notify.service.PushSubscriptionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Web Push 订阅管理接口（docs/notify/design.md 触达扩展）。
 *
 * @description 登记走 upsert（endpoint 唯一键）；注销支持单 endpoint 与全量两种粒度。
 *              公钥查询端点供前端动态取 VAPID key（避免硬编码进构建产物）。
 * @author 开发团队
 */
@Slf4j
@RestController
@RequestMapping("/api/push")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "push.enabled", havingValue = "true")
public class PushController {

    private final PushSubscriptionService subscriptionService;
    private final PushMessageRepository messageRepository;

    @Value("${push.public-key}")
    private String vapidPublicKey;

    @Data
    public static class SubscribeRequest {
        private String endpoint;
        private String p256dh;
        private String auth;
    }

    /** VAPID 公钥（前端 pushManager.subscribe 的 applicationServerKey） */
    @GetMapping("/vapid-public-key")
    public ApiResponse<String> vapidPublicKey() {
        return ApiResponse.success(vapidPublicKey);
    }

    /** 登记订阅（幂等 upsert，同一浏览器重复订阅覆盖密钥） */
    @PostMapping("/subscribe")
    public ApiResponse<Void> subscribe(@RequestAttribute("authUserId") String userId,
                                       @RequestBody SubscribeRequest req,
                                       jakarta.servlet.http.HttpServletRequest http) {
        if (req.getEndpoint() == null || req.getEndpoint().isBlank()
                || req.getP256dh() == null || req.getP256dh().isBlank()
                || req.getAuth() == null || req.getAuth().isBlank()) {
            throw new com.zzh.stock_calculator.common.BusinessException("订阅参数不完整");
        }
        String ua = http.getHeader("User-Agent");
        subscriptionService.subscribe(userId, req.getEndpoint(), req.getP256dh(), req.getAuth(),
                ua != null && ua.length() > 300 ? ua.substring(0, 300) : ua);
        return ApiResponse.success(null);
    }

    /** 注销单个订阅（浏览器 pushManager.unsubscribe 后同步服务端） */
    @PostMapping("/unsubscribe")
    public ApiResponse<Void> unsubscribe(@RequestBody SubscribeRequest req) {
        if (req.getEndpoint() != null && !req.getEndpoint().isBlank()) {
            subscriptionService.unsubscribe(req.getEndpoint());
        }
        return ApiResponse.success(null);
    }

    /** 注销该用户全部订阅 */
    @DeleteMapping("/subscriptions")
    public ApiResponse<Void> unsubscribeAll(@RequestAttribute("authUserId") String userId) {
        subscriptionService.unsubscribeAll(userId);
        return ApiResponse.success(null);
    }

    /** 消息列表（最近 50 条，倒序；漏达补拉通道）；顺带惰性清理历史（仅保留最近 100 条） */
    @GetMapping("/messages")
    public ApiResponse<List<PushMessage>> messages(@RequestAttribute("authUserId") String userId) {
        messageRepository.pruneKeepRecent100(userId);
        return ApiResponse.success(messageRepository.findTop50ByUserIdOrderByCreatedAtDesc(userId));
    }

    /** 未读数（角标） */
    @GetMapping("/messages/unread-count")
    public ApiResponse<Long> unreadCount(@RequestAttribute("authUserId") String userId) {
        return ApiResponse.success(messageRepository.countByUserIdAndReadAtIsNull(userId));
    }

    /** 全部标记已读（幂等）；顺带惰性清理历史（保留最近 100 条，防膨胀） */
    @PostMapping("/messages/read-all")
    public ApiResponse<Void> markAllRead(@RequestAttribute("authUserId") String userId) {
        messageRepository.markAllRead(userId);
        messageRepository.pruneKeepRecent100(userId);
        return ApiResponse.success(null);
    }
}
