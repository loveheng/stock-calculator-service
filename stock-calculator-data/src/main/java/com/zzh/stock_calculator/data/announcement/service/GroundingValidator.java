package com.zzh.stock_calculator.data.announcement.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数值接地校验（设计文档 §4.7/D8）：摘要中提炼的关键数值必须在源切片文本中
 * 可定位（单位族同族直比/异族归一 + 容差），证伪「凭空捏造」数字幻觉。
 * 只能证伪数字凭空捏造，不能证真语义对齐——D8 防线1（Prompt 数值照抄原文）是主力，本组件是保险丝。
 * 失败 → 定向重试 1 次 → GROUNDING_FAIL 终态。已知盲区：大写金额第一版不解析，出现即判失败。
 */
@Component
@ConditionalOnProperty(prefix = "datasvc.worker", name = "enabled", havingValue = "true")
public class GroundingValidator {

    /**
     * 数字+单位联合正则：保留单位标志不做预除算；千分位逗号剥离（全角形态已由 NFKC 归一，双保险）。
     * 单位为扁平列表（万亿 必须在 亿/万 之前），可整体缺省 → 裸数（BARE）。
     */
    private static final Pattern NUMBER_UNIT = Pattern.compile(
            "(\\d[\\d,，]*(?:\\.\\d+)?)\\s*(个百分点|%|％|万亿|亿|万|元|股|手|倍|成)?");

    /** 大写金额（壹佰贰拾万元整）：首字符类禁含 万亿，防「万亿元」误报 */
    private static final Pattern CAPITAL_AMOUNT =
            Pattern.compile("[壹贰叁肆伍陆柒捌玖拾佰仟]{2,}[万亿]?元");

    private static final double MULTIPLIER_TRILLION = 1e12;
    private static final double MULTIPLIER_HUNDRED_MILLION = 1e8;
    private static final double MULTIPLIER_TEN_THOUSAND = 1e4;

    /** 单位族（§4.7）：个百分点与 % 分属不同族（5 个百分点 ≠ 5%） */
    private enum Family {PCT, PP, MULT, MONEY, BARE}

    private record NumberUnit(String digits, String unit, Family family, double value, double raw, int scale) {

        static NumberUnit of(String digits, String unit) {
            String normalized = digits.replace(",", "").replace("，", "");
            BigDecimal decimal = new BigDecimal(normalized);
            double base = decimal.doubleValue();
            Family family;
            double multiplier;
            if (unit == null) {
                family = Family.BARE;
                multiplier = 1;
            } else {
                switch (unit) {
                    case "个百分点" -> {
                        family = Family.PP;
                        multiplier = 1;
                    }
                    case "%", "％" -> {
                        family = Family.PCT;
                        multiplier = 1;
                    }
                    case "倍", "成" -> {
                        family = Family.MULT;
                        multiplier = 1;
                    }
                    default -> {
                        family = Family.MONEY;
                        multiplier = unit.contains("万亿") ? MULTIPLIER_TRILLION
                                : unit.contains("亿") ? MULTIPLIER_HUNDRED_MILLION
                                : unit.contains("万") ? MULTIPLIER_TEN_THOUSAND : 1;
                    }
                }
            }
            return new NumberUnit(digits, unit, family, base * multiplier, base, decimal.scale());
        }
    }

    /**
     * @param summary      LLM 蒸馏摘要
     * @param sourceSlices 选中的源切片文本（合并前逐节点）
     * @return 校验结果（passed=false 时 mismatchDetail 为失配数值明细，"、" 拼接）
     */
    public ValidationResult validate(String summary, List<String> sourceSlices) {
        if (summary == null || summary.isBlank() || sourceSlices == null || sourceSlices.isEmpty()) {
            return new ValidationResult(true, null);
        }
        if (CAPITAL_AMOUNT.matcher(summary).find()) {
            return fail(List.of("摘要含大写金额（§4.7 已知盲区）"));
        }
        List<NumberUnit> summaryNums = extract(summary, false);
        if (summaryNums.isEmpty()) {
            return new ValidationResult(true, null);
        }
        List<NumberUnit> sourceNums = extract(String.join("\n", sourceSlices), true);
        List<String> mismatches = new ArrayList<>();
        for (NumberUnit num : summaryNums) {
            if (!grounded(num, sourceNums)) {
                mismatches.add(num.digits() + (num.unit() == null ? "" : num.unit()) + " 未在原文定位");
            }
        }
        if (mismatches.isEmpty()) {
            return new ValidationResult(true, null);
        }
        return fail(mismatches);
    }

    /** 摘要单数值定位：同族直比（MONEY 用归一值）；MONEY 额外允许 BARE 通配（表头单位场景），比原始值 */
    private boolean grounded(NumberUnit num, List<NumberUnit> sourceNums) {
        for (NumberUnit candidate : sourceNums) {
            boolean sameFamily = candidate.family() == num.family();
            boolean moneyVsBare = num.family() == Family.MONEY && candidate.family() == Family.BARE;
            if (!sameFamily && !moneyVsBare) {
                continue;
            }
            double expected = moneyVsBare ? num.raw() : num.value();
            double actual = moneyVsBare ? candidate.raw() : candidate.value();
            // 容差 = max(摘要小数位 1 ULP, |摘要值| × 10^-6)：同时覆盖四舍五入与截断
            double tolerance = Math.max(Math.pow(10, -num.scale()), Math.abs(expected) * 1e-6);
            if (Math.abs(expected - actual) <= tolerance) {
                return true;
            }
        }
        return false;
    }

    /** includeBare=false：摘要侧跳过裸数（日期/序号安全）；源侧含裸数作 MONEY 通配候选 */
    private static List<NumberUnit> extract(String text, boolean includeBare) {
        List<NumberUnit> result = new ArrayList<>();
        Matcher matcher = NUMBER_UNIT.matcher(text);
        while (matcher.find()) {
            String unit = matcher.group(2);
            if (!includeBare && (unit == null || unit.isEmpty())) {
                continue;
            }
            result.add(NumberUnit.of(matcher.group(1), unit == null || unit.isEmpty() ? null : unit));
        }
        return result;
    }

    private static ValidationResult fail(List<String> mismatches) {
        return new ValidationResult(false, String.join("、", mismatches));
    }

    public record ValidationResult(boolean passed, String mismatchDetail) {
    }

    /** 接地校验最终失败（定向重试仍不过）：上报 GROUNDING_FAIL，由主服务 result 消费端落终态 */
    public static class GroundingFailException extends RuntimeException {
        public GroundingFailException(String message) {
            super(message);
        }
    }
}
