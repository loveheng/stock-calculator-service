package com.zzh.stock_calculator.data.cls;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * CLS 接口参数与请求头（自 main 模块 ClsDayTaskHelp 的参数段移植）。
 * cache 接口内部补 sign；roll 接口由调用方补 sign（与原 getRollData 顺序一致）。
 */
public final class ClsApiParams {

    private ClsApiParams() {}

    public static final String CACHE_URL = "https://www.cls.cn/api/cache";
    public static final String ROLL_URL = "https://www.cls.cn/v1/roll/get_roll_list";

    public static Map<String, String> header() {
        Map<String, String> headMaps = new HashMap<>();
        headMaps.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        headMaps.put("Referer", "https://www.cls.cn/telegraph");
        headMaps.put("Pragma", "no-cache");
        return headMaps;
    }

    /** 电报列表 cache 接口参数（含 sign） */
    public static Map<String, Object> cacheParams() {
        Map<String, Object> params = new TreeMap<>();
        params.put("app", "CailianpressWeb");
        params.put("name", "telegraph");
        params.put("os", "web");
        params.put("sv", "8.7.9");
        params.put("sign", ClsSignUtil.getSign(params));
        return params;
    }

    /** roll 增量接口参数（sign 由调用方补齐） */
    public static Map<String, Object> rollParams(long time, int refreshType, int num) {
        if (refreshType != 1 && refreshType != 2) {
            refreshType = 1;
        }
        Map<String, Object> params = new TreeMap<>();
        params.put("app", "CailianpressWeb");
        params.put("last_time", time); // 当前时间戳（秒）
        params.put("os", "web");
        params.put("refresh_type", refreshType);
        params.put("rn", num);
        params.put("sv", "8.7.9");
        return params;
    }
}
