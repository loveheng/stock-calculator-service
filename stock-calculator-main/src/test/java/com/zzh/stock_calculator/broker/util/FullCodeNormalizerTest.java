package com.zzh.stock_calculator.broker.util;

import com.zzh.stock_calculator.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * fullCode 归一化契约测试（free-canvas v3 §3.8-1：腾讯形态规范输入，非法形状 400）。
 */
class FullCodeNormalizerTest {

    @Test
    void normalizesTencentFormToDictCode() {
        assertEquals("601318", FullCodeNormalizer.toStockCode("sh601318"));
        assertEquals("000001", FullCodeNormalizer.toStockCode("sz000001"));
        assertEquals("920000", FullCodeNormalizer.toStockCode("BJ920000"));
    }

    @Test
    void toleratesBareSixDigitCode() {
        assertEquals("600519", FullCodeNormalizer.toStockCode("600519"));
    }

    @Test
    void rejectsBadShape() {
        assertThrows(BusinessException.class, () -> FullCodeNormalizer.toStockCode("60131"));
        assertThrows(BusinessException.class, () -> FullCodeNormalizer.toStockCode("abc123"));
        assertThrows(BusinessException.class, () -> FullCodeNormalizer.toStockCode("sh6013188"));
        assertThrows(BusinessException.class, () -> FullCodeNormalizer.toStockCode(null));
        assertThrows(BusinessException.class, () -> FullCodeNormalizer.toStockCode("  "));
    }

    @Test
    void dictKeyMatchesDbForms() {
        // DB 实测字典键形态：沪深前缀 / 北交 .BJ 后缀
        assertEquals("sh600745", FullCodeNormalizer.toDictKey("sh600745"));
        assertEquals("sh600745", FullCodeNormalizer.toDictKey("600745"));
        assertEquals("sz000001", FullCodeNormalizer.toDictKey("sz000001"));
        assertEquals("sz300627", FullCodeNormalizer.toDictKey("300627"));
        assertEquals("920000.BJ", FullCodeNormalizer.toDictKey("920000"));
        assertEquals("920000.BJ", FullCodeNormalizer.toDictKey("bj920000"));
    }
}
