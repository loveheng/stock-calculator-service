package com.zzh.stock_calculator.orchestration.planner;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 参数兜底修正（agent-orchestration 4 增强 1②）：填槽后、硬校验前对明显可修的
 * 参数做无感修正而非报错——A股代码补市场后缀、超长时间范围裁剪。
 * 只修「确定无歧义」的形态，修不了的保持原样交给后续硬校验报错。
 */
public final class ParamGuardrail {

    /** 6 位纯数字股票代码（不带后缀） */
    private static final Pattern BARE_CODE = Pattern.compile("^(\\d{6})$");

    /** 「过去/近 N 年/月/天」类时间范围（用于裁剪） */
    private static final Pattern RANGE_YEARS = Pattern.compile("(\\d{2,})\\s*(年|年以内|年内)");

    /** A股时间范围合理上限：数据源实际只覆盖约 20 年，更久视为误述，裁到 20 */
    private static final int MAX_RANGE_YEARS = 20;

    private ParamGuardrail() {}

    /**
     * 对填槽结果就地修正：值命中 6 位数字且槽位名含 stock/code 的补市场后缀
     * （6 开头→.SH，其余→.SZ——北交所 8/4 开头量小暂按 .SZ 兜底，错后缀由下游工具报错兜住）；
     * 槽位名含 range/period 且值为「N年」超限的裁到 20 年。
     */
    public static void correct(ObjectNode filled, JsonNode slots) {
        if (slots == null || !slots.isArray()) {
            return;
        }
        for (JsonNode slot : slots) {
            String name = slot.path("name").asText("");
            JsonNode v = filled.get(name);
            if (v == null || !v.isTextual()) {
                continue;
            }
            String text = v.asText();
            String lower = name.toLowerCase();
            if (lower.contains("stock") || lower.contains("code")) {
                Matcher m = BARE_CODE.matcher(text);
                if (m.matches()) {
                    filled.put(name, text + (text.startsWith("6") ? ".SH" : ".SZ"));
                }
            } else if (lower.contains("range") || lower.contains("period")) {
                Matcher m = RANGE_YEARS.matcher(text);
                if (m.find()) {
                    int years = Integer.parseInt(m.group(1));
                    if (years > MAX_RANGE_YEARS) {
                        filled.put(name, text.replaceFirst("\\d{2,}", String.valueOf(MAX_RANGE_YEARS)));
                    }
                }
            }
        }
    }
}
