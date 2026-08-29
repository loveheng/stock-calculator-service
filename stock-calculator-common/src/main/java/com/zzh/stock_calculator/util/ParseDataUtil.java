package com.zzh.stock_calculator.util;

public class ClsParseDataUtil {

    public static String asStr(Object val) {
        return val == null ? null : String.valueOf(val);
    }

    public static int toInt(Object val, int defaultVal) {
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    public static Long toLong(Object val, Long defaultVal) {
        if (val instanceof Number n) return n.longValue();
        if (val instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    public static long toLong(Object val, long defaultVal) {
        if (val instanceof Number n) return n.longValue();
        if (val instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    public static java.math.BigDecimal toBigDecimal(Object val) {
        if (val instanceof Number n) return java.math.BigDecimal.valueOf(n.doubleValue());
        if (val instanceof String s) {
            try { return new java.math.BigDecimal(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    public static boolean toBool(Object val, boolean defaultVal) {
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return "true".equalsIgnoreCase(s) || "1".equals(s);
        if (val instanceof Number n) return n.intValue() == 1;
        return defaultVal;
    }
}
