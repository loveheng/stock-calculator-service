package com.zzh.stock_calculator.announcement.controller;

import com.zzh.stock_calculator.announcement.dto.SubscriptionDtos;
import com.zzh.stock_calculator.announcement.entity.AnnouncementSubscription;
import com.zzh.stock_calculator.announcement.service.AnnouncementSubscriptionService;
import com.zzh.stock_calculator.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 公告订阅端点（设计文档 §4）：登录会话鉴权（WebConfig 拦截 /api/announcement/subscriptions/**），
 * authUserId = 会话内用户 UUID 文本（CustomStatController 同款）。
 */
@RestController
@RequestMapping("/api/announcement/subscriptions")
@RequiredArgsConstructor
public class AnnouncementSubscriptionController {

    private final AnnouncementSubscriptionService subscriptionService;

    /** 订阅：true=新建（触发抓取事件），false=已存在幂等跳过 */
    @PostMapping
    public ApiResponse<Boolean> subscribe(@RequestAttribute("authUserId") String userId,
                                          @RequestBody SubscriptionDtos.SubscribeRequest request) {
        boolean created = subscriptionService.subscribe(UUID.fromString(userId), request.getStockId());
        return ApiResponse.success(created);
    }

    /** 退订：true=删除成功，false=本就未订阅 */
    @DeleteMapping("/{stockId}")
    public ApiResponse<Boolean> unsubscribe(@RequestAttribute("authUserId") String userId,
                                            @PathVariable String stockId) {
        return ApiResponse.success(subscriptionService.unsubscribe(UUID.fromString(userId), stockId));
    }

    /** 我的订阅清单（创建时间倒序） */
    @GetMapping
    public ApiResponse<SubscriptionDtos.SubscriptionListResponse> list(
            @RequestAttribute("authUserId") String userId) {
        List<SubscriptionDtos.SubscriptionItem> items = subscriptionService.list(UUID.fromString(userId)).stream()
                .map(this::toItem)
                .toList();
        return ApiResponse.success(SubscriptionDtos.SubscriptionListResponse.builder().items(items).build());
    }

    private SubscriptionDtos.SubscriptionItem toItem(AnnouncementSubscription subscription) {
        return SubscriptionDtos.SubscriptionItem.builder()
                .stockId(subscription.getStockId())
                .orgId(subscription.getOrgId())
                .createdAt(subscription.getCreatedAt())
                .build();
    }
}
