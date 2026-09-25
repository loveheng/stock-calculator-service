package com.zzh.stock_calculator.broker.util;

import com.zzh.stock_calculator.common.BusinessException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * fullCode 规范形态归一化（free-canvas v3 §三统一约定 / §3.8-1 契约裁决）：
 * 前端规范输入为腾讯形态（sh601318），main 归一化为 6 位数字字典码过 crawler 股票字典校验
 * （字典键形态混杂：沪深 sh600745 前缀 / 北交所 920000.BJ 后缀，校验用 existsBySixDigit 尾匹配），
 * 非法形状直接 400——转换成本留在后端，前端零转换。
 */
public final class FullCodeNormalizer {

    /** 腾讯形态（sh/sz/bj + 6 位）或裸 6 位数字（宽容兼容，字典校验兜真实性） */
    private static final Pattern FULL_CODE = Pattern.compile("^(?:sh|sz|bj)?(\\d{6})$");

    private FullCodeNormalizer() {
    }

    /** 归一化为 6 位字典码；形状非法抛 400 */
    public static String toStockCode(String fullCode) {
        if (fullCode == null || fullCode.isBlank()) {
            throw new BusinessException(400, "fullCode 缺失");
        }
        Matcher matcher = FULL_CODE.matcher(fullCode.trim().toLowerCase());
        if (!matcher.matches()) {
            throw new BusinessException(400, "非法 fullCode（需腾讯形态如 sh601318）: " + fullCode);
        }
        return matcher.group(1);
    }

    /**
     * 归一化为字典键形态（mcp fetch_kline 字典按此形态解析）：市场推断与 DB 实测形态对齐——
     * 6 沪 → sh 前缀 / 4·8·9 北交 → .BJ 后缀 / 其余深 → sz 前缀。
     */
    public static String toDictKey(String fullCode) {
        String code = toStockCode(fullCode);
        if (code.startsWith("6")) {
            return "sh" + code;
        }
        return code.startsWith("4") || code.startsWith("8") || code.startsWith("9")
                ? code + ".BJ" : "sz" + code;
    }
}
