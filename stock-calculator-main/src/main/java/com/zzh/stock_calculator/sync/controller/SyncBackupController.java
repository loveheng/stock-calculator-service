package com.zzh.stock_calculator.sync.controller;

import com.zzh.stock_calculator.common.ApiResponse;
import com.zzh.stock_calculator.sync.dto.SyncDtos.PushOutcome;
import com.zzh.stock_calculator.sync.dto.SyncDtos.RateLimitData;
import com.zzh.stock_calculator.sync.dto.SyncDtos.SyncMetaDto;
import com.zzh.stock_calculator.sync.dto.SyncDtos.SyncPullDto;
import com.zzh.stock_calculator.sync.dto.SyncDtos.SyncPushRequest;
import com.zzh.stock_calculator.sync.dto.SyncDtos.SyncPushResultDto;
import com.zzh.stock_calculator.sync.service.SyncBackupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务端密文同步控制层（3 端点，docs/server-sync-backend-implementation.md §7）。
 *
 * @description 鉴权复用 AuthInterceptor——成功时注入 @RequestAttribute("authUserId") String
 *              （与 CopilotController 同法，UUID 文本，绝不从请求体读取）；401 由拦截器统一直写。
 *              E4：OK → ApiResponse.success；CONFLICT/RATED 由本层组装带 data 响应
 *              （BusinessException 无 data 通道），40001/40002/40003/40401 仍由
 *              GlobalExceptionHandler 统一转信封（data=null）。
 */
@Slf4j
@RestController
@RequestMapping("/api/sync/backup")
@RequiredArgsConstructor
public class SyncBackupController {

    private final SyncBackupService syncBackupService;

    @GetMapping("/meta")
    public ApiResponse<SyncMetaDto> meta(@RequestAttribute("authUserId") String userId) {
        return ApiResponse.success(syncBackupService.meta(userId));
    }

    @GetMapping
    public ApiResponse<SyncPullDto> pull(@RequestAttribute("authUserId") String userId) {
        return ApiResponse.success(syncBackupService.pull(userId));
    }

    @PutMapping
    public ApiResponse<?> push(@RequestAttribute("authUserId") String userId,
                               @RequestBody SyncPushRequest request) {
        PushOutcome o = syncBackupService.push(userId, request);
        return switch (o.getType()) {
            case OK -> ApiResponse.success(SyncPushResultDto.of(o.getVersion(), o.isDeduped()));
            case CONFLICT -> ApiResponse.builder()
                    .code(o.isEmptyConflict() ? 40902 : 40901)
                    .message("版本冲突")
                    .data(o.getMeta())
                    .build();
            case RATED -> ApiResponse.builder()
                    .code(42901)
                    .message("上传过于频繁")
                    .data(RateLimitData.of(o.getRetryAfterSeconds()))
                    .build();
        };
    }
}
