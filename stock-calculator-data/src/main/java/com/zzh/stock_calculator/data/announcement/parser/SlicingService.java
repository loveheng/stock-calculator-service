package com.zzh.stock_calculator.data.announcement.parser;

import com.zzh.stockcalc.contract.message.SliceSelection;
import com.zzh.stockcalc.contract.message.StructureNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 物理切片（设计文档 §4.4/D4/§4.5）：nodeId → offset 区间截取，零字符串 find()。
 * 逐节点 sha256（UTF-8）留档 → 区间并集合并（父章节选中时子节自动并入，防重复拼接）
 * → 固定分隔符 "\n\n" 拼接。哈希基线三要素写入 SliceSelection（评审③）。
 * joinSelected/sliceTexts 供阶段二蒸馏与接地校验复用同一合并逻辑。
 */
@Component
@ConditionalOnProperty(prefix = "datasvc.worker", name = "enabled", havingValue = "true")
public class SlicingService {

    public static final String HASH_ALGORITHM = "SHA-256";
    public static final String CHARSET = "UTF-8";
    public static final String JOIN_SEPARATOR = "\n\n";

    /**
     * @param route 路由来源标记（skeleton_no_llm / llm_stage1）
     */
    public SliceSelection slice(String cleanedText, List<StructureNode> nodes, List<String> nodeIds, String route) {
        List<StructureNode> selected = resolveSelected(nodes, nodeIds);
        List<int[]> ranges = new ArrayList<>(selected.size());
        List<String> hashes = new ArrayList<>(selected.size());
        for (StructureNode node : selected) {
            String sliceText = cleanedText.substring(node.getStartOffset(), node.getEndOffset());
            hashes.add(sha256Hex(sliceText));
            ranges.add(new int[]{node.getStartOffset(), node.getEndOffset()});
        }

        List<int[]> merged = mergeRanges(ranges);
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < merged.size(); i++) {
            if (i > 0) {
                joined.append(JOIN_SEPARATOR);
            }
            joined.append(cleanedText, merged.get(i)[0], merged.get(i)[1]);
        }

        return SliceSelection.builder()
                .nodeIds(selected.stream().map(StructureNode::getNodeId).toList())
                .sliceRanges(ranges)
                .sliceSha256(hashes)
                .hashAlgorithm(HASH_ALGORITHM)
                .charset(CHARSET)
                .joinSeparator(JOIN_SEPARATOR)
                .route(route)
                .joinedLength(joined.length())
                .build();
    }

    /** 阶段二蒸馏输入：选中切片按并集合并 + 固定分隔符拼接（与 slice() 同一合并逻辑） */
    public String joinSelected(String cleanedText, List<StructureNode> nodes, List<String> nodeIds) {
        List<int[]> merged = mergeRanges(resolveRanges(cleanedText, nodes, nodeIds));
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < merged.size(); i++) {
            if (i > 0) {
                joined.append(JOIN_SEPARATOR);
            }
            joined.append(cleanedText, merged.get(i)[0], merged.get(i)[1]);
        }
        return joined.toString();
    }

    /** 接地校验源切片：与 nodeIds/sliceRanges/sliceSha256 对齐的逐节点文本 */
    public List<String> sliceTexts(String cleanedText, List<StructureNode> nodes, List<String> nodeIds) {
        List<StructureNode> selected = resolveSelected(nodes, nodeIds);
        List<String> texts = new ArrayList<>(selected.size());
        for (StructureNode node : selected) {
            texts.add(cleanedText.substring(node.getStartOffset(), node.getEndOffset()));
        }
        return texts;
    }

    private List<StructureNode> resolveSelected(List<StructureNode> nodes, List<String> nodeIds) {
        List<StructureNode> selected = new ArrayList<>();
        for (String nodeId : nodeIds) {
            nodes.stream()
                    .filter(n -> n.getNodeId().equals(nodeId))
                    .findFirst()
                    .ifPresent(selected::add);
        }
        selected.sort(Comparator.comparingInt(StructureNode::getStartOffset));
        return selected;
    }

    private List<int[]> resolveRanges(String cleanedText, List<StructureNode> nodes, List<String> nodeIds) {
        List<StructureNode> selected = resolveSelected(nodes, nodeIds);
        List<int[]> ranges = new ArrayList<>(selected.size());
        for (StructureNode node : selected) {
            ranges.add(new int[]{node.getStartOffset(), node.getEndOffset()});
        }
        return ranges;
    }

    private List<int[]> mergeRanges(List<int[]> ranges) {
        List<int[]> merged = new ArrayList<>();
        for (int[] range : ranges) {
            if (!merged.isEmpty() && range[0] <= merged.get(merged.size() - 1)[1]) {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], range[1]);
            } else {
                merged.add(new int[]{range[0], range[1]});
            }
        }
        return merged;
    }

    static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
