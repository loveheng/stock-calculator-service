package com.zzh.stock_calculator.common;

import lombok.Getter;

/**
 * profile If-Match 版本冲突（《设计》§D.4.4，决策 B5）。
 *
 * @description 409 语义需在 data 中携带服务端 updatedAt 供前端冲突处理，
 *              由 AuthController 捕获后组装响应（不经 GlobalExceptionHandler，避免泛化丢 data）。
 */
@Getter
public class ProfileConflictException extends RuntimeException {

    /** 服务端当前版本（ISO-8601），前端刷新版本后重新决策，不得盲目重试覆盖 */
    private final String serverUpdatedAt;

    public ProfileConflictException(String serverUpdatedAt) {
        super("档案版本冲突，请以助记词恢复");
        this.serverUpdatedAt = serverUpdatedAt;
    }
}
