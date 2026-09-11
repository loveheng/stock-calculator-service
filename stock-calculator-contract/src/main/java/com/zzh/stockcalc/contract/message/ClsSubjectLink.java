package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文章-题材关联（对应 cls_article_subject，只含关联 ID 对，无元数据快照）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClsSubjectLink {

    private Long articleId;
    private Long subjectId;
}
