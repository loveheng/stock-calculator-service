package com.zzh.stock_calculator.data.announcement.parser;

import com.zzh.stock_calculator.data.announcement.AnnouncementParseProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 文本清洗管道 v0.2（设计文档 §4.2 Step 5）：
 * 1) 目录页整体跳过（前 5 页：独立「目录」行或 ≥3 条点划线页码行，防结构树重复，§4.3 避坑）
 * 2) 跨页重复行剔除（页眉/页脚动态检测：出现 ≥3 页且占比 ≥ header-repeat-ratio，弃静态硬编码）
 * 3) 页码行剔除（纯数字 / - 3 - / 3/10 / 第 3 页 共 10 页）
 * 4) 跨行同段拼接（行尾无强断句标点 + 两侧均非标题/项目符号 + 段首长度 ≥ 20 + 数字边界强断）
 * 5) 扫描页/近空页清空（单页有效字符 < no-text-min-chars-per-page）
 *
 * <p>偏移体系不变量（D4 前提）：仅做页内变换，返回列表长度 == 入参页数，
 * 目录页/空页以空串占位——ExtractedDocument.pageStartOffsets 与 pageCount 语义不受影响。
 * 已知限制：跨页段落不拼接（页界截断段落保留换行）；列对齐型表格行仍会串行，
 * 由 D8 数字接地校验兜底。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "datasvc.worker", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class TextCleaner {

    /** 目录扫描页数上限（目录一般在前 2~3 页，取 5 留余量） */
    private static final int TOC_SCAN_PAGES = 5;
    /** 同段拼接的段首长度下限：短行（小标题/单句）不吸收后续行 */
    private static final int JOIN_MIN_PREV_LEN = 20;
    /** 页眉/页脚判定最小出现页数（防小文档 30% 阈值误伤正文行） */
    private static final int REPEAT_MIN_PAGES = 3;
    /** 页码行：纯数字 / - 3 - / 3/10 / 第 3 页(共 10 页) */
    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "^[-—–~]?\\s*\\d{1,4}\\s*[-—–~]?$"
                    + "|^\\d{1,4}\\s*/\\s*\\d{1,4}$"
                    + "|^第\\s*\\d{1,4}\\s*页(\\s*[,，]?\\s*共\\s*\\d{1,4}\\s*页)?$");
    /** 目录点划线行：3+ 个点/省略号/间隔号后跟页码 */
    private static final Pattern TOC_LEADER = Pattern.compile("[.·…]{3,}\\s*\\d{1,4}\\s*$");
    /** 强断句标点：行尾出现即视为段落结束（含全/半角与冒号） */
    private static final String BREAK_PUNCT = "。！？；…：:!?;.";
    /** 项目符号起始字符：段落起点，禁止被拼接到上行 */
    private static final String BULLETS = "●•○◦■□▲△◆◇①②③④⑤⑥⑦⑧⑨⑩*";

    private final AnnouncementParseProperties properties;

    /**
     * @param pages 逐页文本（已 normalize：NFKC + \r 统一 + 增补平面替换）
     * @return 清洗后逐页文本（长度不变；空页为空串占位）
     */
    public List<String> clean(List<String> pages) {
        AnnouncementParseProperties.Clean cfg = properties.getClean();
        int pageCount = pages.size();
        if (pageCount == 0) {
            return pages;
        }
        List<List<String>> pageLines = new ArrayList<>(pageCount);
        for (String page : pages) {
            pageLines.add(new ArrayList<>(Arrays.asList(page.split("\n", -1))));
        }

        // 1) 目录页整体跳过
        int droppedToc = 0;
        int scanLimit = Math.min(TOC_SCAN_PAGES, pageCount);
        for (int i = 0; i < scanLimit; i++) {
            if (isTocPage(pageLines.get(i))) {
                pageLines.set(i, new ArrayList<>());
                droppedToc++;
            }
        }

        // 2) 跨页重复行（页眉/页脚）检测
        Set<String> repeated = detectRepeatedLines(pageLines, cfg.getHeaderRepeatRatio());

        // 3) 逐页剔除页码行与重复行 → 同段拼接
        List<String> result = new ArrayList<>(pageCount);
        for (List<String> lines : pageLines) {
            List<String> kept = new ArrayList<>(lines.size());
            for (String line : lines) {
                String s = collapse(line);
                if (s.isEmpty()) {
                    kept.add("");
                } else if (!PAGE_NUMBER.matcher(s).matches() && !repeated.contains(s)) {
                    kept.add(s);
                }
            }
            result.add(String.join("\n", dewrap(kept)));
        }

        // 4) 扫描页/近空页清空
        int emptied = 0;
        for (int i = 0; i < result.size(); i++) {
            if (effectiveLength(result.get(i)) < cfg.getNoTextMinCharsPerPage()) {
                result.set(i, "");
                emptied++;
            }
        }
        log.debug("清洗完成 pages={} tocDropped={} repeatedLines={} emptied={}",
                pageCount, droppedToc, repeated.size(), emptied);
        return result;
    }

    /** 目录页判定：独立「目录」行（容忍内部空格）或 ≥3 条点划线页码行 */
    private static boolean isTocPage(List<String> lines) {
        int leaderCount = 0;
        for (String line : lines) {
            String s = collapse(line);
            if (s.isEmpty()) {
                continue;
            }
            if (s.replace(" ", "").equals("目录")) {
                return true;
            }
            if (TOC_LEADER.matcher(s).find()) {
                leaderCount++;
            }
        }
        return leaderCount >= 3;
    }

    /**
     * 跨页重复行检测：行归一后按页去重计数，
     * 出现页数 ≥ REPEAT_MIN_PAGES 且占比 ≥ ratio 判定为页眉/页脚。
     * 标题/页码/点划线行不参与（避免误删真实标题或污染统计）。
     */
    private Set<String> detectRepeatedLines(List<List<String>> pageLines, double ratio) {
        Map<String, Set<Integer>> linePages = new HashMap<>();
        for (int p = 0; p < pageLines.size(); p++) {
            for (String line : pageLines.get(p)) {
                String s = collapse(line);
                if (s.length() < 2 || s.length() > 60
                        || PAGE_NUMBER.matcher(s).matches()
                        || TitlePatterns.isHeadingLike(s)
                        || TOC_LEADER.matcher(s).find()) {
                    continue;
                }
                linePages.computeIfAbsent(s, k -> new HashSet<>()).add(p);
            }
        }
        Set<String> repeated = new HashSet<>();
        int pageCount = pageLines.size();
        for (Map.Entry<String, Set<Integer>> entry : linePages.entrySet()) {
            int count = entry.getValue().size();
            if (count >= REPEAT_MIN_PAGES && count / (double) pageCount >= ratio) {
                repeated.add(entry.getKey());
            }
        }
        return repeated;
    }

    /**
     * 页内同段拼接：硬折行合并为完整段落。
     * 标题/项目符号行/强断句行永远独立成行（保住建树行首匹配前提）。
     */
    private List<String> dewrap(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        StringBuilder para = new StringBuilder();
        for (String line : lines) {
            if (line.isEmpty()) {
                flushTo(out, para);
                continue;
            }
            if (para.length() == 0 || !canJoin(para, line)) {
                flushTo(out, para);
                para.append(line);
            } else {
                para.append(line);
            }
            if (TitlePatterns.isHeadingLike(line) || startsWithBullet(line) || endsWithBreak(line)) {
                flushTo(out, para);
            }
        }
        flushTo(out, para);
        return out;
    }

    private boolean canJoin(StringBuilder para, String next) {
        if (para.length() < JOIN_MIN_PREV_LEN) {
            return false;
        }
        String prev = para.toString();
        if (endsWithBreak(prev) || TitlePatterns.isHeadingLike(prev) || startsWithBullet(prev)) {
            return false;
        }
        if (TitlePatterns.isHeadingLike(next) || startsWithBullet(next)) {
            return false;
        }
        // 数字拼接防护：两侧边界均为数字/百分号时强断（表格行串行最毒的形态，
        // 如 "…占比 57.5" + "432%"），防接地校验数值被破坏
        char prevLast = prev.charAt(prev.length() - 1);
        char nextFirst = next.charAt(0);
        return !(isDigitLike(prevLast) && isDigitLike(nextFirst));
    }

    private void flushTo(List<String> out, StringBuilder para) {
        if (para.length() > 0) {
            out.add(para.toString());
            para.setLength(0);
        }
    }

    private static boolean endsWithBreak(String s) {
        if (s.isEmpty()) {
            return false;
        }
        return BREAK_PUNCT.indexOf(s.charAt(s.length() - 1)) >= 0;
    }

    private static boolean startsWithBullet(String s) {
        return !s.isEmpty() && BULLETS.indexOf(s.charAt(0)) >= 0;
    }

    private static boolean isDigitLike(char c) {
        return Character.isDigit(c) || c == '%';
    }

    /** 行归一：压缩连续空白为单空格 + 去首尾（作为比较键与输出行共用） */
    private static String collapse(String line) {
        return line.replaceAll("\\s+", " ").strip();
    }

    /** 单页有效字符数（去空白） */
    private static int effectiveLength(String page) {
        return page.replaceAll("\\s", "").length();
    }
}
