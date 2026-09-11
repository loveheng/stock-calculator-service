package com.zzh.stock_calculator.announcement.parser;

import com.zzh.stock_calculator.announcement.dto.ExtractedDocument;
import com.zzh.stockcalc.contract.message.StructureNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 标题结构树构建（设计文档 §4.3/D4）：三级标题正则（TitlePatterns 共享库）+ 绝对字符偏移绑定。
 * 页眉页脚剔除与目录页跳过已上移至 TextCleaner（v0.2）；页码回跳的节点视为页眉页脚误识别直接丢弃。
 */
@Component
public class StructureTreeBuilder {

    /** 标题行长度上限：超限视为正文 */
    private static final int MAX_TITLE_LEN = 60;
    /** 标题行长度下限 */
    private static final int MIN_TITLE_LEN = 4;

    public List<StructureNode> build(ExtractedDocument doc) {
        String text = doc.getCleanedText();
        List<StructureNode> nodes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int[] pageStarts = doc.getPageStartOffsets();
        int pageIdx = 0;
        int chapter = 0;
        int section = 0;
        int sub = 0;
        int pos = 0;
        while (pos < text.length()) {
            int nl = text.indexOf('\n', pos);
            int lineEnd = nl < 0 ? text.length() : nl;
            pageIdx = advancePage(pageIdx, pageStarts, pos);
            String line = text.substring(pos, lineEnd);
            String title = matchTitle(line);
            if (title != null && seen.add(title)) {
                String nodeId;
                int level;
                if (TitlePatterns.L1.matcher(title).matches()) {
                    chapter++;
                    section = 0;
                    sub = 0;
                    level = 1;
                    nodeId = String.valueOf(chapter);
                } else if (TitlePatterns.L2.matcher(title).matches()) {
                    section++;
                    sub = 0;
                    level = 2;
                    nodeId = chapter + "-" + section;
                } else {
                    sub++;
                    level = 3;
                    nodeId = chapter + "-" + section + "-" + sub;
                }
                int page = pageIdx + 1;
                // 页码回跳 = 页眉页脚/目录残留误识别，丢弃
                if (nodes.isEmpty() || page >= nodes.get(nodes.size() - 1).getPage()) {
                    nodes.add(StructureNode.builder()
                            .nodeId(nodeId)
                            .level(level)
                            .title(title)
                            .page(page)
                            .startOffset(pos)
                            .build());
                }
            }
            pos = lineEnd + 1;
        }
        // 绑定 endOffset = 下一节点 startOffset，末节点 = 文末（D4 切片零 find()）
        for (int i = 0; i < nodes.size(); i++) {
            int end = i + 1 < nodes.size() ? nodes.get(i + 1).getStartOffset() : text.length();
            nodes.get(i).setEndOffset(end);
        }
        if (nodes.isEmpty()) {
            // §5 兜底三：零节点短公告 → root 单节点覆盖全文，跳过阶段一路由
            nodes.add(StructureNode.builder()
                    .nodeId("root")
                    .level(0)
                    .title("全文")
                    .page(1)
                    .startOffset(0)
                    .endOffset(text.length())
                    .build());
        }
        return nodes;
    }

    /**
     * 标题判定：行首无缩进（辅助校验）+ 长度 [4,60] + 无目录点划线 + 命中三级正则之一。
     */
    private String matchTitle(String line) {
        String stripped = line.strip();
        if (stripped.length() < MIN_TITLE_LEN || stripped.length() > MAX_TITLE_LEN) {
            return null;
        }
        if (line.length() != line.stripLeading().length()) {
            return null;    // 行首不缩进（§4.3 辅助校验）
        }
        if (stripped.contains("...") || stripped.contains("……")) {
            return null;    // 目录点划线（v0.1 简化防线，完整目录页跳过 TODO v0.2）
        }
        if (TitlePatterns.L1.matcher(stripped).matches() || TitlePatterns.L2.matcher(stripped).matches()
                || TitlePatterns.L3.matcher(stripped).matches()) {
            return stripped;
        }
        return null;
    }

    private int advancePage(int pageIdx, int[] pageStarts, int offset) {
        while (pageIdx + 1 < pageStarts.length && pageStarts[pageIdx + 1] <= offset) {
            pageIdx++;
        }
        return pageIdx;
    }
}
