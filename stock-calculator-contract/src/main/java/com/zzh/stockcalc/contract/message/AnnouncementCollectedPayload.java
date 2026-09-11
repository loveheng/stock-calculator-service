package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * result.announcement.collected 的 payload（设计文档 §4.3）：
 * collector → 主服务的公告元数据（不含 PDF，主服务 existsByAnnouncementId 幂等落 PENDING）。
 * seDate 为 ISO yyyy-MM-dd 文本（契约侧不引入 java.time，规避序列化行为差异）；
 * adjunctSize 单位 KB（CNINFO 原始语义），主服务据此做超体积前置拒绝。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementCollectedPayload {

    /** CNINFO 唯一公告标识（幂等锚点） */
    private String announcementId;

    /** 清洗后标题（剥高亮标记 + HTML 实体解码） */
    private String title;

    /** PDF 相对路径（worker 自下载依据） */
    private String adjunctUrl;

    /** 公告日期（ISO yyyy-MM-dd 文本） */
    private String seDate;

    /** 股票代码（六位数字） */
    private String secCode;

    /** 股票简称 */
    private String secName;

    /** 附件大小（KB，CNINFO 原始语义；可空） */
    private Long adjunctSize;

    /** CNINFO orgId 解析结果（主服务回填订阅行，R3 对账） */
    private String orgId;
}
