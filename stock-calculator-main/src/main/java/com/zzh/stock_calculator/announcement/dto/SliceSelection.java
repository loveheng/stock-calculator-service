package com.zzh.stock_calculator.announcement.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 切片选择与哈希基线（设计文档 §4.4）：哈希重放比对三要素（算法/编码/拼接分隔符）
 * 显式写入 JSON，杜绝后续重放时基线歧义。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SliceSelection {

    /** 选中的 nodeId 数组（骨架期 = 全选；S5 后 = LLM 阶段一返回） */
    private List<String> nodeIds;

    /** 与 nodeIds 对齐的逐节点切片区间 [start, end)（合并前） */
    private List<int[]> sliceRanges;

    /** 与 nodeIds 对齐的逐节点 sha256（UTF-8），重放比对锚点 */
    private List<String> sliceSha256;

    /** 哈希算法基线 */
    private String hashAlgorithm;

    /** 哈希输入编码基线 */
    private String charset;

    /** 多切片拼接分隔符基线 */
    private String joinSeparator;

    /** 路由来源：skeleton_no_llm / llm_stage1 */
    private String route;

    /** 蒸馏 Prompt 版本留档（AnnouncementDistillService.PROMPT_VERSION） */
    private String promptVersion;

    /** 接地校验失配明细（仅 GROUNDING_FAIL 终态时写入，§4.7） */
    private List<String> groundingMismatches;

    /** 合并拼接后的待蒸馏文本长度 */
    private int joinedLength;
}
