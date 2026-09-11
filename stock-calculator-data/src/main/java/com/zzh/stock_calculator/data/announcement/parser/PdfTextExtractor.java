package com.zzh.stock_calculator.data.announcement.parser;

import com.zzh.stock_calculator.data.announcement.dto.ExtractedDocument;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 纯文本抽取（设计文档 §4.2，S1 实证落码）：
 * byte[] 落 tmpdir 瞬态文件 → Loader.loadPDF(file, createTempFileOnlyStreamCache())
 * 有界内存解析（3.x 默认纯内存缓冲，必须显式传临时文件流缓存，§8.1 S1）→ 逐页抽取 →
 * v0.2 清洗管道（TextCleaner：页眉页脚/页码剔除 + 目录页跳过 + 同段拼接）→
 * finally 删临时文件（D3 用后即删，不落盘存储）。
 */
@Component
@ConditionalOnProperty(prefix = "datasvc.worker", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class PdfTextExtractor {

    public static final String EXTRACTOR_VERSION = "0.2.0";

    private final TextCleaner textCleaner;

    public ExtractedDocument extract(byte[] pdfBytes) throws IOException {
        Path tmp = Files.createTempFile("ann-pdf-", ".pdf");
        try {
            Files.write(tmp, pdfBytes);
            try (PDDocument document = Loader.loadPDF(tmp.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
                int pageCount = document.getNumberOfPages();
                List<String> pages = new ArrayList<>(pageCount);
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                for (int p = 1; p <= pageCount; p++) {
                    stripper.setStartPage(p);
                    stripper.setEndPage(p);
                    pages.add(normalize(stripper.getText(document)));
                }
                return ExtractedDocument.of(pageCount, textCleaner.clean(pages));
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * 清洗 v0.1 基础（§4.2 Step 5 的偏移安全子集）：
     * 1) NFKC 归一化（全角/半角统一，如 （一） vs (一)）；
     * 2) \r 统一为 \n（保证逐行偏移推进正确）；
     * 3) 增补平面字符（code point > 0xFFFF）替换为 '□'——Java String 为 UTF-16，
     *    替换后 char 偏移 == code point 偏移（D12），切片永不跨字符边界。
     * v0.2 深度清洗（页眉页脚/目录页/同段拼接）由 TextCleaner 在 extract() 内接力。
     */
    static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        StringBuilder sb = new StringBuilder(nfkc.length());
        nfkc.codePoints().forEach(cp -> sb.appendCodePoint(cp > 0xFFFF ? 0x25A1 : cp));
        return sb.toString();
    }
}
