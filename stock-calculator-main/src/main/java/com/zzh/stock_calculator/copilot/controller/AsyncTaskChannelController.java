package com.zzh.stock_calculator.copilot.controller;

import com.zzh.stock_calculator.copilot.service.AsyncTaskChannelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 异步任务进度 SSE 订阅端点（步 6-3b）：前端创建异步任务拿到 correlationId 后，
 * 经本端点建立长连接；终态事件到达由 AsyncTaskChannelService 推 task_result 事件
 * 并收口连接；断连重连恢复以 user_async_task_log 审计终态为准。
 */
@RestController
@RequestMapping("/api/copilot/async-tasks")
@RequiredArgsConstructor
public class AsyncTaskChannelController {

    private final AsyncTaskChannelService asyncTaskChannelService;

    @GetMapping(value = "/{correlationId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@PathVariable("correlationId") String correlationId) {
        return asyncTaskChannelService.subscribe(correlationId);
    }
}
