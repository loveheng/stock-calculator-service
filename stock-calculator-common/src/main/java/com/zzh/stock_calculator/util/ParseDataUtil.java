package com.zzh.stock_calculator.util;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ParseDataUtil {

    /** Boot 4 默认 Jackson 3（tools.jackson）；readValue 抛 unchecked JacksonException，catch 语义不变 */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> JSON_STRING_LIST_TYPE =
            new TypeReference<List<String>>() {};

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


    // ========== JSON 数组解析 ==========
    @SuppressWarnings("unchecked")
    public static List<String> parseJsonStrList(Object raw) {
        if (raw instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object o : list) {
                if (o != null) result.add(String.valueOf(o));
            }
            return result.isEmpty() ? null : result;
        }
        if (raw instanceof String str && !str.isBlank() && str.startsWith("[")) {
            try {
                return JSON_MAPPER.readValue(str, JSON_STRING_LIST_TYPE);
            } catch (Exception e) {
                log.warn("failed to parse JSON array: {}", str);
            }
        }
        return null;
    }
}
