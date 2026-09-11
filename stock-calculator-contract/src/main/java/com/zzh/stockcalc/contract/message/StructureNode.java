package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 结构树节点（设计文档 §4.3/D4）：建树期即绑定绝对字符偏移，
 * 切片按 nodeId → offset 截取，消除字符串 find() 的重名/失配两类 bug。
 * <p>2026-09-11 阶段 4 任务 1 自主服务 announcement/dto 下沉至 contract：
 * result.announcement.done 载荷引用此类型，主服务与数据服务共用单一来源（R2 防协议漂移）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StructureNode {

    /** 层级编号（章="1"、节="1-2"、小节="1-2-3"）；空树兜底根节点 = "root" */
    private String nodeId;

    /** 1 章 / 2 节 / 3 小节；root = 0 */
    private int level;

    /** 标题文本（清洗后行原文） */
    private String title;

    /** 所在页（1-based，pageStartOffsets 推导） */
    private int page;

    /** cleanedText 起始偏移（normalize 保证 char == code point，D12） */
    private int startOffset;

    /** 终止偏移 = 下一节点 startOffset（末节点 = 文末），建树期绑定 */
    private int endOffset;
}
