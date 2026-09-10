package com.zzh.stock_calculator.announcement.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GroundingValidator 纯单测（§4.7/D8）：单位族同族直比/异族归一 + 容差 + 大写金额盲区 + 裸数通配。
 */
class GroundingValidatorTest {

    private final GroundingValidator validator = new GroundingValidator();

    @Test
    void exactPercentMatch() {
        var result = validator.validate("本季度利润率为 57.54%，同比提升",
                List.of("报告期内利润率为 57.54%"));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void roundingTolerancePass() {
        // 摘要舍入显示精度（57.54）vs 原文全精度（57.5432）：容差覆盖
        var result = validator.validate("占比 57.54%", List.of("占比 57.5432%"));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void moneyUnitNormalization() {
        // 亿元 ↔ 万元 归一（亿 = 10^8，万 = 10^4）
        var result = validator.validate("亏损 12000 万元", List.of("亏损 1.2亿元"));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void percentagePointsVsPercentMismatch() {
        // 「个百分点」与「%」分属不同族：5 个百分点 ≠ 5%
        var result = validator.validate("提升 5 个百分点", List.of("提升 5%"));
        assertThat(result.passed()).isFalse();
        assertThat(result.mismatchDetail()).contains("5个百分点");
    }

    @Test
    void fabricatedNumberFails() {
        var result = validator.validate("营收 12.8亿元", List.of("营收 3.2亿元"));
        assertThat(result.passed()).isFalse();
        assertThat(result.mismatchDetail()).contains("12.8");
    }

    @Test
    void capitalAmountBlindSpotFails() {
        // 大写金额第一版不解析，出现即判失败（§4.7 已知盲区明示）
        var result = validator.validate("收到补偿款壹佰贰拾万元整", List.of("补偿款 120万元"));
        assertThat(result.passed()).isFalse();
        assertThat(result.mismatchDetail()).contains("大写金额");
    }

    @Test
    void bareNumberWildcardForMoney() {
        // 表头单位场景：摘要带单位、源为裸数（含千分位逗号）→ MONEY vs BARE 通配比原始值
        var result = validator.validate("募集资金 12345.67万元", List.of("合计 12,345.67"));
        assertThat(result.passed()).isTrue();
    }

    @Test
    void dateNumbersSkippedInSummary() {
        // 摘要侧裸数（日期/序号）跳过，不触发校验
        var result = validator.validate("2026 年 8 月 28 日，公司董事会审议通过相关议案",
                List.of("2026 年 8 月 28 日"));
        assertThat(result.passed()).isTrue();
    }
}
