package com.zzh.stock_calculator.crawler.controller;

import com.zzh.stock_calculator.common.ApiResponse;
import com.zzh.stock_calculator.crawler.mq.TaskPublisher;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.message.HistorySyncTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * CLS 历史补录管理端点（设计文档 §3.2/§4.3，MQ 单路径终态）：
 * /history/start 改发 task.history.sync 触发消息，由数据服务 collector 执行区间拉取
 * （幂等入库由主服务 result 消费端承担），执行完回 result.cls.history.report（日志级）。
 * /history/stop 在 MQ 模式下为无操作：区间任务有界自终止，无需远程停止。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/sync")
@RequiredArgsConstructor
public class SynclsHistorycontroller {

    private final TaskPublisher taskPublisher;

    /** 管理令牌：未配置时端点整体拒绝，防止 /api/admin/sync 被匿名触发 */
    @Value("${crawler.admin-token:}")
    private String adminToken;

    @PostMapping("/history/start")
    public ApiResponse<Void> startHistorySync(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                              @RequestParam(defaultValue = "1787903769") long startTime,
                                              @RequestParam(defaultValue = "1693320189") long endTime) {
        if (!tokenOk(token)) {
            return ApiResponse.fail(403, "管理令牌缺失或不匹配");
        }
        String requestId = UUID.randomUUID().toString();
        taskPublisher.dispatchTask(MessageType.TASK_HISTORY_SYNC, HistorySyncTask.builder()
                .requestId(requestId)
                .source("cls")
                .startTime(startTime)
                .endTime(endTime)
                .build());
        log.info("历史补录任务已下发 collector requestId={} window=[{}, {}]", requestId, endTime, startTime);
        return ApiResponse.success(null);
    }

    @PostMapping("/history/stop")
    public ApiResponse<Void> stopHistorySync(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!tokenOk(token)) {
            return ApiResponse.fail(403, "管理令牌缺失或不匹配");
        }
        log.info("历史补录停止请求：MQ 模式下为区间有界任务，无需停止（no-op）");
        return ApiResponse.success(null);
    }

    private boolean tokenOk(String token) {
        return !adminToken.isBlank() && adminToken.equals(token);
    }
}
