package com.zzh.stock_calculator.crawler.controller;
import com.zzh.stock_calculator.common.ApiResponse;
import com.zzh.stock_calculator.crawler.task.HistoryClsDayTask;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/sync")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "crawler", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SynclsHistorycontroller {

    private final HistoryClsDayTask historyClsDayTask;

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
        historyClsDayTask.startHistorySync(startTime, endTime);
        return ApiResponse.success(null);
    }

    @PostMapping("/history/stop")
    public ApiResponse<Void> stopHistorySync(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!tokenOk(token)) {
            return ApiResponse.fail(403, "管理令牌缺失或不匹配");
        }
        historyClsDayTask.stopSync();
        return ApiResponse.success(null);
    }

    private boolean tokenOk(String token) {
        return !adminToken.isBlank() && adminToken.equals(token);
    }
}
