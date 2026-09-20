package com.zzh.stock_calculator.mcp.kb;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RSS/Atom 解析：标题型 feed 去重、全文型取 content:encoded、双时间格式容错（mcp-blogger-kb M1b） */
class KbRssFeedParserTest {

    private final KbRssFeedParser parser = new KbRssFeedParser();

    @Test
    void parseRssTitleOnlyFeedDedupsDescription() {
        String xml = String.join("\n",
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
                "<rss version=\"2.0\" xmlns:content=\"http://purl.org/rss/1.0/modules/content/\">",
                "<channel><title>中国政府网</title>",
                "<item><title>",
                "  国务院办公厅关于进一步加强烟花爆竹全链条安全监管的意见",
                "</title><link>https://www.gov.cn/a.htm</link>",
                "<description>",
                "  国务院办公厅关于进一步加强烟花爆竹全链条安全监管的意见",
                "Delivered by PolitePaul service</description>",
                "<pubDate>Fri, 18 Sep 2026 11:28:10 -0000</pubDate></item>",
                "<item><title>全文型条目</title><link>https://www.gov.cn/b.htm</link>",
                "<content:encoded><![CDATA[这里是政策全文第一段。]]></content:encoded>",
                "<pubDate>Fri, 18 Sep 2026 10:00:00 GMT</pubDate></item>",
                "</channel></rss>");

        List<KbEntryDraft> entries = parser.parse(xml);

        assertEquals(2, entries.size());
        // 标题型：description 复读标题被去重，块 = 标题 + 链接
        assertEquals("国务院办公厅关于进一步加强烟花爆竹全链条安全监管的意见\n链接: https://www.gov.cn/a.htm",
                entries.get(0).getContent());
        // 数值时区 -0000 → 系统时区 +08:00 = 19:28:10
        assertEquals(LocalDateTime.of(2026, 9, 18, 19, 28, 10), entries.get(0).getPublishedAt());
        // 全文型：content:encoded 优先
        assertTrue(entries.get(1).getContent().contains("这里是政策全文第一段。"));
        assertTrue(entries.get(1).getContent().endsWith("链接: https://www.gov.cn/b.htm"));
        assertEquals(LocalDateTime.of(2026, 9, 18, 18, 0, 0), entries.get(1).getPublishedAt());
    }

    @Test
    void parseAtomEntryWithIsoDate() {
        String xml = String.join("\n",
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
                "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>t</title>",
                "<entry><title>Atom 条目</title>",
                "<link rel=\"alternate\" href=\"https://x.example/a\"/>",
                "<content>Atom 正文内容。</content>",
                "<published>2026-09-18T08:00:00Z</published></entry></feed>");

        List<KbEntryDraft> entries = parser.parse(xml);

        assertEquals(1, entries.size());
        assertTrue(entries.get(0).getContent().contains("Atom 正文内容。"));
        assertTrue(entries.get(0).getContent().endsWith("链接: https://x.example/a"));
        assertEquals(LocalDateTime.of(2026, 9, 18, 16, 0, 0), entries.get(0).getPublishedAt());
    }

    @Test
    void rejectsNonFeedXml() {
        assertThrows(IllegalStateException.class, () -> parser.parse("<html><body>not a feed</body></html>"));
        assertThrows(IllegalStateException.class, () -> parser.parse("这不是 XML"));
    }
}
