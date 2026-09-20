package com.zzh.stock_calculator.mcp.kb;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 微博备份解析：头块跳过 / 条目=日期行+正文 / 纯媒体条目与标记行剔除（mcp-blogger-kb M1） */
class KbWeiboBackupParserTest {

    private final KbWeiboBackupParser parser = new KbWeiboBackupParser();

    private static final String SEP = "--------------------------------------------------";

    @Test
    void matchesWeiboBackupHeaderOnly() {
        String raw = "微博备份记录\n" + SEP + "\n备份账号：x\n" + SEP
                + "\n2026-09-20 06:03 | 原创\n正文\n" + SEP + "\n";
        assertTrue(parser.matches(raw));
        assertFalse(parser.matches("随便一个普通文本\n第二行内容"));
    }

    @Test
    void parseEntriesSkipsHeaderMediaAndMarkerLines() {
        String raw = String.join("\n",
                "微博备份记录",
                SEP,
                "备份账号：",
                "账号链接：https://weibo.com/u/2188093987",
                "备份用户：happernewday",
                SEP,
                "2026-09-20 06:03 | 原创",
                "在全球大型主权经济体国家当中，现在中国已经是利率最低的。",
                SEP,
                "2026-09-20 05:11 | 转发",
                "特朗普终于忍不住要准备TACO了。",
                "转发自麻辣新鲜：油价在今天凌晨的时候突然跌了一下。",
                SEP,
                "2026-09-20 05:09 | 转发",
                "转发微博",
                "转发自干弟Nick在学英语：突发：特朗普宣布相关消息。",
                SEP,
                "2026-09-20 05:00 | 原创",
                "分享图片",
                SEP,
                "2026-09-19 06:41 | 转发",
                "转发微博",
                SEP,
                "");
        List<KbEntryDraft> entries = parser.parse(raw);

        assertEquals(3, entries.size());
        assertEquals(LocalDateTime.of(2026, 9, 20, 6, 3), entries.get(0).getPublishedAt());
        assertEquals("在全球大型主权经济体国家当中，现在中国已经是利率最低的。",
                entries.get(0).getContent());
        assertTrue(entries.get(1).getContent().startsWith("特朗普终于忍不住"));
        assertTrue(entries.get(1).getContent().contains("转发自麻辣新鲜"));
        assertEquals("转发自干弟Nick在学英语：突发：特朗普宣布相关消息。",
                entries.get(2).getContent());
    }
}
