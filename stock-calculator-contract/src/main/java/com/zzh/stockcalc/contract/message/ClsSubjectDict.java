package com.zzh.stockcalc.contract.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 题材字典（对应 cls_subject，主键 subjectId，主服务 upsert 不存在才插入）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClsSubjectDict {

    private Long subjectId;
    private String subjectName;
    private Long plateId;
    private String channel;
}
