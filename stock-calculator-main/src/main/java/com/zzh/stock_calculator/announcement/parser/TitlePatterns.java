package com.zzh.stock_calculator.announcement.parser;

import java.util.regex.Pattern;

/**
 * 标题模式共享库（设计文档 §4.3）：StructureTreeBuilder 建树与 TextCleaner
 * 防误拼接共用同一套正则（单一事实源，避免两处规则漂移）。
 */
public final class TitlePatterns {

    /** 一级（章）：第X章/节 */
    public static final Pattern L1 = Pattern.compile("^第[一二三四五六七八九十百]+[章节]\\s*\\S.*");

    /** 二级（节）：一、 */
    public static final Pattern L2 = Pattern.compile("^[一二三四五六七八九十]+[、.]\\s*\\S.*");

    /** 三级：（一）/ 1. / 1、（1.0 类小数不误配：(?!\\d)） */
    public static final Pattern L3 = Pattern.compile(
            "^[（(][一二三四五六七八九十]+[)）]\\s*\\S.*|^\\d+[、.](?!\\d)\\s*\\S.*");

    private TitlePatterns() {
    }

    /** 行文本是否命中任一级标题模式（strip 后匹配） */
    public static boolean isHeadingLike(String line) {
        String s = line.strip();
        if (s.isEmpty()) {
            return false;
        }
        return L1.matcher(s).matches() || L2.matcher(s).matches() || L3.matcher(s).matches();
    }
}
