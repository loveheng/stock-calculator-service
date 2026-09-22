package com.zzh.stock_calculator.orchestration.config;

/**
 * 全链路 TraceId 透传持有器（步 6-0）：PassportFilter 同层捕获 main 下传的
 * W3C traceparent，取 trace-id 段存 ThreadLocal；工具面（TaskTool/DispatchTool）
 * 优先取外部透传值，无透传才本地生成 UUID——保证幂等键/日志键全链路一致。
 * <p>MCP server transport 在 HTTP 请求线程上同步分派工具调用，ThreadLocal 生命周期
 * 与请求线程一致（filter finally 清理防线程池串号）。
 */
public final class TraceIdHolder {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TraceIdHolder() {
    }

    public static void set(String traceId) {
        HOLDER.set(traceId);
    }

    /** 当前链路 traceId：优先外部透传，无则 null（由调用方本地生成兜底） */
    public static String get() {
        return HOLDER.get();
    }

    /** 透传优先取值工具：external 非空用之，否则 ThreadLocal，再否则本地生成 */
    public static String resolve(String external) {
        if (external != null && !external.isBlank()) {
            return external;
        }
        String held = HOLDER.get();
        return held != null ? held : java.util.UUID.randomUUID().toString();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
