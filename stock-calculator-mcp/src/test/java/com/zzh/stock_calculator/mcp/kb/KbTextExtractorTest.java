package com.zzh.stock_calculator.mcp.kb;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用内存构造的迷你 EPUB 验证 spine 顺序抽取与 HTML 剥标签。
 */
class KbTextExtractorTest {

    private final KbTextExtractor extractor = new KbTextExtractor();

    @Test
    void epubExtractsInSpineOrderWithTextStripped() throws Exception {
        byte[] epub = miniEpub();
        Path file = Files.createTempFile("kb-test", ".epub");
        Files.write(file, epub);
        try {
            List<KbSection> sections = extractor.extract(file);
            assertEquals(2, sections.size(), "近空白页应跳过");
            // spine 声明顺序 ch2 在前（与文件名无关）
            assertEquals("第二章 标题", sections.get(0).getTitle());
            assertTrue(sections.get(0).getText().startsWith("第二章 标题"));
            assertEquals("第一章 标题", sections.get(1).getTitle());
            assertTrue(sections.get(1).getText().contains("K线 & 形态"));
            assertTrue(sections.get(1).getText().contains("<script>未剥离"));
            assertFalse(sections.get(1).getText().contains("<p>"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void assertFalse(boolean cond) {
        org.junit.jupiter.api.Assertions.assertFalse(cond);
    }

    @Test
    void txtSingleSection() throws Exception {
        Path file = Files.createTempFile("kb-test", ".txt");
        Files.writeString(file, "均线为王正文内容");
        try {
            List<KbSection> sections = extractor.extract(file);
            assertEquals(1, sections.size());
            assertTrue(sections.get(0).getTitle().endsWith(".txt") == false);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private byte[] miniEpub() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("META-INF/container.xml"));
            zip.write("""
                    <?xml version="1.0"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>""".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("OEBPS/content.opf"));
            zip.write("""
                    <?xml version="1.0"?>
                    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                      <manifest>
                        <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                      </manifest>
                      <spine><itemref idref="c2"/><itemref idref="c1"/></spine>
                    </package>""".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("OEBPS/ch2.xhtml"));
            zip.write("""
                    <html><body><h2>第二章 标题</h2>
                    <p>蜡烛图反转形态：锤子线与上吊线互为镜像，同样的形态出现在趋势顶部与趋势底部含义截然相反。</p>
                    <p>锤子线要求下影线至少达到实体的两倍，且几乎没有上影线。</p>
                    <script>console.log('noise')</script></body></html>""".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("OEBPS/ch1.xhtml"));
            zip.write("""
                    <html><body><h1>第一章 标题</h1>
                    <p>K线 &amp; 形态<br/>次行内容 &lt;敏感&gt;</p>
                    <style>.x{color:red}</style><p>&lt;script&gt;未剥离&lt;/script&gt;为转义文本</p>
                    </body></html>""".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bos.toByteArray();
    }
}
