package com.zzh.stock_calculator.mcp.kb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * RSS 2.0 / Atom feed 解析（JDK DOM，与 KbTextExtractor 同款零依赖套路）。
 * 三档 feed 都能吃：全文型（content:encoded / atom:content 优先）、摘要型（description/summary）、
 * 标题型（description 复读标题——互相包含去重，块=标题+链接，当条目雷达用）。
 * 条目时间 RFC-822（RSS）与 ISO-8601（Atom）双格式容错，带时区的统一折算系统时区（时效口径一致）。
 */
@Slf4j
@Component
public class KbRssFeedParser {

    public List<KbEntryDraft> parse(String xml) {
        Document doc;
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("feed 解析失败（非合法 XML）: " + e.getMessage(), e);
        }
        String root = doc.getDocumentElement().getNodeName().toLowerCase();
        List<KbEntryDraft> out = new ArrayList<>();
        if (root.contains("rss") || root.contains("rdf")) {
            parseItems(doc, out);
        } else if (root.contains("feed")) {
            parseAtom(doc, out);
        } else {
            throw new IllegalStateException("非 RSS/Atom feed: root=<" + root + ">");
        }
        log.info("feed 解析完成: {} 条条目", out.size());
        return out;
    }

    private void parseItems(Document doc, List<KbEntryDraft> out) {
        NodeList items = doc.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            addEntry(out,
                    childText(item, "title"),
                    childText(item, "link"),
                    childText(item, "content:encoded"),
                    childText(item, "description"),
                    firstDate(childText(item, "pubDate")));
        }
    }

    private void parseAtom(Document doc, List<KbEntryDraft> out) {
        NodeList entries = doc.getElementsByTagName("entry");
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            addEntry(out,
                    childText(entry, "title"),
                    atomLink(entry),
                    childText(entry, "content"),
                    childText(entry, "summary"),
                    firstDate(childText(entry, "published"), childText(entry, "updated")));
        }
    }

    private void addEntry(List<KbEntryDraft> out, String title, String link,
                          String fullText, String fallbackText, LocalDateTime publishedAt) {
        String t = strip(title);
        String body = !strip(fullText).isBlank() ? strip(fullText) : strip(fallbackText);
        StringBuilder sb = new StringBuilder();
        if (!t.isBlank()) {
            sb.append(t);
        }
        // 标题型 feed 的 description 复读标题（互相包含）→ 去重跳过，块=标题+链接
        if (!body.isBlank() && !body.equals(t) && !body.contains(t) && !t.contains(body)) {
            sb.append(sb.isEmpty() ? "" : "\n\n").append(body);
        }
        if (!strip(link).isBlank()) {
            sb.append(sb.isEmpty() ? "" : "\n").append("链接: ").append(strip(link));
        }
        String content = sb.toString().strip();
        if (content.isBlank()) {
            return;
        }
        out.add(new KbEntryDraft(publishedAt, content));
    }

    private String childText(Element parent, String name) {
        NodeList nodes = parent.getElementsByTagName(name);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : "";
    }

    /** Atom link：优先 rel=alternate / 无 rel，其次首个带 href 的 */
    private String atomLink(Element entry) {
        NodeList links = entry.getElementsByTagName("link");
        String fallback = "";
        for (int i = 0; i < links.getLength(); i++) {
            Element link = (Element) links.item(i);
            String href = link.getAttribute("href");
            if (href.isBlank()) {
                continue;
            }
            String rel = link.getAttribute("rel");
            if (rel.isBlank() || rel.equals("alternate")) {
                return href;
            }
            if (fallback.isEmpty()) {
                fallback = href;
            }
        }
        return fallback;
    }

    private LocalDateTime firstDate(String... candidates) {
        for (String c : candidates) {
            LocalDateTime d = parseDate(c);
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    /** RFC-822 / ISO-8601 / 纯日期时间三段容错；无法解析返回 null（条目仍入库，时间列留空） */
    private LocalDateTime parseDate(String raw) {
        String s = raw == null ? "" : raw.strip();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH))
                    .atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception ignored) {
            // 下一格式
        }
        try {
            return OffsetDateTime.parse(s,
                            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH))
                    .atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception ignored) {
            // 下一格式
        }
        try {
            return OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception ignored) {
            // 下一格式
        }
        try {
            return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception ignored) {
            // 全部失败
        }
        log.warn("feed 条目时间无法解析: {}", s);
        return null;
    }

    private String strip(String s) {
        return s == null ? "" : s.strip();
    }
}
