package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * result.cls.article 的 payload（设计文档 §4.3）：解析好的电报文章 + 字典 + 关联，
 * 对应主服务 saveArticleWithRelations 的事务写入参数。
 * 幂等锚点 = article.id：主服务已存在即整条跳过（重复投递无副作用）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClsArticlePayload {

    private ClsArticleDto article;
    private List<ClsSubjectDict> subjectDicts;
    private List<ClsStockDict> stockDicts;
    private List<ClsSubjectLink> subjectLinks;
    private List<ClsStockLink> stockLinks;
}
