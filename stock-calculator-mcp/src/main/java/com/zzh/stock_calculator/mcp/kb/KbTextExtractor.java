package com.zzh.stock_calculator.mcp.kb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 书源文本抽取：.epub（JDK zip + DOM 解析 container.xml/OPF，**按 spine 顺序**读取——
 * 实测书源 chapter 文件名乱序，名字序会打乱章节）与 .txt（整文件单节）。
 * HTML 剥标签：块级标签转换行，实体解码，压缩空白。
 */
@Slf4j
@Component
public class KbTextExtractor {

    private static final Pattern BLOCK_TAG = Pattern.compile(
            "</?(p|div|h[1-6]|br|li|tr|table|section|blockquote)[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern STYLE_SCRIPT = Pattern.compile(
            "<(script|style)[^>]*>.*?</\\1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ENTITY = Pattern.compile("&(amp|lt|gt|quot|apos|#x?[0-9a-fA-F]+);");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    public List<KbSection> extract(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".epub")) {
            return extractEpub(file);
        }
        if (name.endsWith(".txt")) {
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                return List.of(new KbSection(stripSuffix(file.getFileName().toString()), normalize(text)));
            } catch (Exception e) {
                throw new IllegalStateException("txt 读取失败: " + file, e);
            }
        }
        throw new IllegalStateException("暂不支持的书源格式: " + file.getFileName() + "（支持 .epub / .txt）");
    }

    private List<KbSection> extractEpub(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            String opfPath = findOpf(zip);
            String opfDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/')) : "";
            Element opf = parseXml(zip.getInputStream(zip.getEntry(opfPath)));

            Map<String, String> hrefById = new HashMap<>();
            var items = opf.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                String href = item.getAttribute("href");
                if (!href.isEmpty()) {
                    hrefById.put(item.getAttribute("id"), resolvePath(opfDir, href));
                }
            }

            List<KbSection> sections = new ArrayList<>();
            var refs = opf.getElementsByTagName("itemref");
            for (int i = 0; i < refs.getLength(); i++) {
                String idref = ((Element) refs.item(i)).getAttribute("idref");
                String href = hrefById.get(idref);
                if (href == null) {
                    continue;
                }
                ZipEntry entry = zip.getEntry(href);
                if (entry == null) {
                    log.warn("spine 条目缺失: {}", href);
                    continue;
                }
                String html = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                String text = htmlToText(html);
                if (text.length() < 50) {
                    continue; // 封面/目录/空白页
                }
                sections.add(new KbSection(sectionTitle(html, href), text));
            }
            log.info("epub 抽取完成: {} 节, 共 {} 字", sections.size(),
                    sections.stream().mapToLong(s -> s.getText().length()).sum());
            return sections;
        } catch (Exception e) {
            throw new IllegalStateException("epub 抽取失败: " + file, e);
        }
    }

    private String findOpf(ZipFile zip) throws Exception {
        Element container = parseXml(zip.getInputStream(zip.getEntry("META-INF/container.xml")));
        var roots = container.getElementsByTagName("rootfile");
        for (int i = 0; i < roots.getLength(); i++) {
            Element root = (Element) roots.item(i);
            if (root.getAttribute("media-type").contains("oebps-package")) {
                return root.getAttribute("full-path");
            }
        }
        throw new IllegalStateException("container.xml 无 OPF rootfile");
    }

    private Element parseXml(InputStream in) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var doc = factory.newDocumentBuilder().parse(in);
        return doc.getDocumentElement();
    }

    private String resolvePath(String dir, String href) {
        if (href.startsWith("/")) {
            return href.substring(1);
        }
        return Paths.get(dir.isEmpty() ? "." : dir).resolve(href).normalize().toString();
    }

    /** 标题取首个 h1-h3 文本，取不到用文件名 */
    private String sectionTitle(String html, String href) {
        Matcher m = Pattern.compile("<h[1-3][^>]*>(.*?)</h[1-3]>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(html);
        if (m.find()) {
            String t = normalize(stripTags(m.group(1)));
            if (!t.isBlank()) {
                return t.substring(0, Math.min(t.length(), 100));
            }
        }
        return stripSuffix(href.substring(href.lastIndexOf('/') + 1));
    }

    private String htmlToText(String html) {
        return normalize(stripTags(html));
    }

    private String stripTags(String html) {
        String s = STYLE_SCRIPT.matcher(html).replaceAll("");
        s = BLOCK_TAG.matcher(s).replaceAll("\n");
        s = ANY_TAG.matcher(s).replaceAll("");
        s = decodeEntities(s);
        return s;
    }

    private String decodeEntities(String s) {
        StringBuilder out = new StringBuilder(s.length());
        Matcher m = ENTITY.matcher(s);
        int last = 0;
        while (m.find()) {
            out.append(s, last, m.start());
            out.append(entityChar(m.group(1)));
            last = m.end();
        }
        out.append(s, last, s.length());
        return out.toString();
    }

    private String entityChar(String entity) {
        return switch (entity) {
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos" -> "'";
            default -> {
                String digits = entity.startsWith("#x") || entity.startsWith("#X")
                        ? entity.substring(2) : entity.substring(1);
                int radix = entity.startsWith("#x") || entity.startsWith("#X") ? 16 : 10;
                try {
                    yield Character.toString(Integer.parseInt(digits, radix));
                } catch (NumberFormatException e) {
                    yield "";
                }
            }
        };
    }

    private String normalize(String text) {
        String s = text.replace("\r\n", "\n").replace('\r', '\n');
        s = s.replaceAll("[ \\t\\u00a0]+", " ");
        s = BLANK_LINES.matcher(s).replaceAll("\n\n");
        return s.strip();
    }

    private String stripSuffix(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
