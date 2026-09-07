package com.zzh.stock_calculator.customstat.controller;

import com.zzh.stock_calculator.common.ApiResponse;
import com.zzh.stock_calculator.customstat.dto.CustomStatDtos.CustomStatListResponse;
import com.zzh.stock_calculator.customstat.service.CustomStatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自定义统计定义持久化控制层（D17 契约 3 端点：docs/custom-stats-server-sync.md §4）。
 *
 * @description 鉴权复用 AuthInterceptor——成功时注入 @RequestAttribute("authUserId") String
 *              （与 SyncBackupController 同法，UUID 文本，绝不从请求体读取）；
 *              401 由拦截器统一直写（前端静默降级不弹窗）。
 *              PUT 体为完整定义 JSON：以 String 原样接收（防具名 DTO 前向兼容丢字段），
 *              校验与存储职责在 Service。校验类 40001 由 GlobalExceptionHandler 统一转信封。
 */
@Slf4j
@RestController
@RequestMapping("/api/custom-stats")
@RequiredArgsConstructor
public class CustomStatController {

    private final CustomStatService customStatService;

    /** 当前用户全部定义（updated_at_client 倒序）；空库返回空数组，非 404 */
    @GetMapping
    public ApiResponse<CustomStatListResponse> list(@RequestAttribute("authUserId") String userId) {
        return ApiResponse.success(customStatService.list(userId));
    }

    /** upsert 单条（按 (userId, defId) 幂等覆盖）；成功 data=null */
    @PutMapping("/{defId}")
    public ApiResponse<Void> upsert(@RequestAttribute("authUserId") String userId,
                                    @PathVariable("defId") String defId,
                                    @RequestBody String payload) {
        customStatService.upsert(userId, defId, payload);
        return ApiResponse.success(null);
    }

    /** 幂等删除：物理 DELETE，不存在也返回 200 */
    @DeleteMapping("/{defId}")
    public ApiResponse<Void> delete(@RequestAttribute("authUserId") String userId,
                                    @PathVariable("defId") String defId) {
        customStatService.delete(userId, defId);
        return ApiResponse.success(null);
    }
}
