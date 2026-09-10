package com.zzh.stock_calculator.announcement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 公告 1:1 溯源表（设计文档 §3/D5/D7）：仅存结构树与切片选择 JSONB，
 * 正文不落库——追溯 = 重抓 PDF → 重抽 → 重建树 → nodeId 按偏移重放切片。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "announcement_content")
public class AnnouncementContent {

    /** 主键 = announcement.id（1:1；DDL 物理外键 ON DELETE CASCADE） */
    @Id
    @Column(name = "announcement_id")
    private Long announcementId;

    /** PdfTextExtractor 版本号：重放时判断是否需要重抽 */
    @Column(name = "extractor_version", columnDefinition = "text")
    private String extractorVersion;

    /** 清洗后文本 code point 数（非 UTF-16 char 数，D12） */
    @Column(name = "char_count")
    private Integer charCount;

    @Column(name = "page_count")
    private Integer pageCount;

    /** 结构树 JSON：List&lt;StructureNode&gt;（nodeId/title/page/startOffset/endOffset） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structure_json", columnDefinition = "jsonb")
    private String structureJson;

    /** 切片选择 JSON：SliceSelection（nodeIds/sliceRanges/sha256 + 哈希基线三要素） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "selection_json", columnDefinition = "jsonb")
    private String selectionJson;
}
