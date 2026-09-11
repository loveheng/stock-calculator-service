package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * result.announcement.done 的 payload（设计文档 §4.3，D7 两段式修订）：
 * worker → 主服务的公告处理成果，只带 content + summary 文本与结构元数据，
 * **不含向量**——向量经 task.embedding.compute(kind=announcement, text=summary) 二段下发，
 * 沿 result.embedding.done 复用阶段 3 全链（确定性 UUID upsert + 断点续传语义 1:1）。
 * content/summary 为 ISO 文本直传；structure/selection 为结构留档（主服务检索侧使用）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementDonePayload {

    /** CNINFO 唯一公告标识（幂等锚点，content 1:1 upsert） */
    private String announcementId;

    /** 抽取器版本留档（PDFBox 管线版本号） */
    private String extractorVersion;

    /** 清洗后正文总字符数 */
    private Integer charCount;

    /** 有效页数 */
    private Integer pageCount;

    /** 清洗后全文（诊断随行；主服务不落正文——announcement 域 D5，
     *  announcement_content 仅落 extractorVersion/charCount/pageCount/structure/selection） */
    private String content;

    /** 蒸馏摘要（主服务入库 + 二段向量化文本同源） */
    private String summary;

    /** 结构树留档 */
    private List<StructureNode> structure;

    /** 切片选择与哈希基线留档 */
    private SliceSelection selection;
}
