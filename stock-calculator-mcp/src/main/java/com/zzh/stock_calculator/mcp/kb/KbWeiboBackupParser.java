package com.zzh.stock_calculator.mcp.kb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微博备份导出解析（mcp-blogger-kb M1）。备份工具导出格式：
 * 文件头（微博备份记录/备份账号/…）+ 「YYYY-MM-DD HH:mm | 原创|转发」行 + 正文 + 长横线分隔线。
 * 一条微博即一个观点单元，物理边界天然成立，不走 600/80 启发式切块；
 * 发布时间随条目提取，供观点时效排序（行情判断类偏新近）。
 * 「分享图片/分享视频」纯媒体条目与「转发微博」标记行剔除，剔除后正文为空的条目跳过。
 */
@Slf4j
@Component
public class KbWeiboBackupParser {

    private static final Pattern SEPARATOR = Pattern.compile("^\\s*-{10,}\\s*$");
    private static final Pattern DATE_LINE =
            Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2})\\s*\\|\\s*(\\S+)\\s*$");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String HEADER_MARK = "微博备份记录";
    private static final Set<String> MARKER_LINES = Set.of("转发微博", "分享图片", "分享视频");

    /** 首个非空行含备份头标记即判定为微博备份格式，否则回落通用 600/80 切块 */
    public boolean matches(String raw) {
        return raw.lines()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .findFirst()
                .map(s -> s.contains(HEADER_MARK))
                .orElse(false);
    }

    public List<KbEntryDraft> parse(String raw) {
        List<KbEntryDraft> entries = new ArrayList<>();
        List<String> segment = new ArrayList<>();
        for (String line : raw.replace("\r\n", "\n").split("\n", -1)) {
            if (SEPARATOR.matcher(line).matches()) {
                collectEntry(segment, entries);
                segment.clear();
            } else {
                segment.add(line);
            }
        }
        collectEntry(segment, entries);
        log.info("微博备份解析完成: {} 条可入库条目", entries.size());
        return entries;
    }

    private void collectEntry(List<String> segment, List<KbEntryDraft> entries) {
        List<String> lines = segment.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        if (lines.isEmpty() || lines.get(0).contains(HEADER_MARK) || lines.get(0).contains("备份账号")) {
            return;
        }
        Matcher m = DATE_LINE.matcher(lines.get(0));
        if (!m.find()) {
            log.debug("跳过无日期行片段: {}", lines.get(0));
            return;
        }
        String body = lines.stream().skip(1)
                .filter(s -> !MARKER_LINES.contains(s))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        if (body.isBlank()) {
            return;
        }
        entries.add(new KbEntryDraft(LocalDateTime.parse(m.group(1), DATE_FMT), body));
    }
}
