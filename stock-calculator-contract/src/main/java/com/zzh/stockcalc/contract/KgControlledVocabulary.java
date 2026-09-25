package com.zzh.stockcalc.contract;

import java.util.List;

/**
 * kg 域受控词表（docs/ai-pipeline/cls-news-kg.md §8 prompt 规则 4 / §9 融合归一）：
 * 与 MQ 常量同规格下沉 contract——data 侧 SYSTEM_PROMPT 声明的「允许输出」与 main 侧
 * KgFuseService 归一时的「允许入库」必须是同一份，两边各写一份会出现「prompt 放行、
 * 落库却被改成兜底值」的隐性不一致（改词表的人看不到另一侧）。
 *
 * <p>词表变更即契约变更：调整后既往抽取结果与新词表不再同口径，存量 kg_relation.predicate
 * 需按 §9 的对账口径自行决定是否回改。</p>
 */
public final class KgControlledVocabulary {

    private KgControlledVocabulary() {
    }

    /** 受控谓词（兜底值本身必须在表内，否则归一后仍会被判为越界） */
    public static final List<String> PREDICATES = List.of(
            "出台", "发布", "召开", "签署", "合作", "任命", "增长", "下降",
            "投资", "扩大", "禁止", "推进", "其他"
    );

    /** 表外谓词的归一目标值 */
    public static final String PREDICATE_FALLBACK = "其他";

    /** prompt 注入形态：斜杠分隔，与 prompt 规则 4 的行文口径一致 */
    public static final String PREDICATES_PROMPT = String.join("/", PREDICATES);
}
