package com.zzh.stock_calculator.common;

/**
 * E2EE 用户服务业务码（docs/e2ee-auth-backend-design.md §D.4.1），配合 ApiResponse 信封使用。
 *
 * @description HTTP 状态除拦截器 401 外恒 200（决策 B8）；前端适配器按 code 语义分支，不解析 message。
 */
public final class AuthErrorCode {

    /** 未认证 / 会话失效（前端转 SIGNED_OUT 本地清理，D7 路径） */
    public static final int UNAUTHORIZED = 401;

    /** profile 缺行（前端合法中间态，驱动孤儿引导 / 补传） */
    public static final int NOT_FOUND = 404;

    /** 邮箱已注册 / If-Match 版本冲突（按端点区分语义） */
    public static final int CONFLICT = 409;

    /** 限流 */
    public static final int TOO_MANY_REQUESTS = 429;

    private AuthErrorCode() {
    }
}
