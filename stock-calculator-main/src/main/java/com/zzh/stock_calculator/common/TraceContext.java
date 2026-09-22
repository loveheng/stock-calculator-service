package com.zzh.stock_calculator.common;

/**
 * W3C Trace Context 透传（步 6-0）：main 是链路源头，生成 traceparent（version 00，
 * 32hex trace-id + 16hex span-id + flags 01），经 HTTP header 下传编排器，
 * 编排器侧优先接受外部透传、无透传才本地生成。
 * <p>轻量实现（不引 OpenTelemetry SDK）：本服务与编排器之间只需要单跳透传 +
 * 全链路统一 traceId，日志检索按 trace-id 段 grep 即可。
 */
public final class TraceContext {

    public static final String TRACEPARENT_HEADER = "traceparent";

    private TraceContext() {
    }

    /** 生成 W3C traceparent：00-<32hex>-<16hex>-01 */
    public static String newTraceparent() {
        String traceId = randomHex(16);
        String spanId = randomHex(8);
        return "00-" + traceId + "-" + spanId + "-01";
    }

    /** W3C 合法性粗校验：非空、分隔结构对、trace-id 非 32 个 0 */
    public static boolean isValid(String traceparent) {
        if (traceparent == null) {
            return false;
        }
        String[] parts = traceparent.trim().split("-");
        if (parts.length != 4 || parts[0].length() != 2
                || parts[1].length() != 32 || parts[2].length() != 16 || parts[3].length() != 2) {
            return false;
        }
        return !parts[1].chars().allMatch(c -> c == '0');
    }

    /** 从 traceparent 提取 trace-id 段（日志/幂等键统一用这一段）；非法返回 null */
    public static String traceIdOf(String traceparent) {
        if (!isValid(traceparent)) {
            return null;
        }
        return traceparent.trim().split("-")[1];
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        new java.security.SecureRandom().nextBytes(buf);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : buf) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
