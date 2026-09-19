package com.zzh.stockcalc.contract.message;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 《新闻联播》要闻的结构化抽取结果（docs/ai-pipeline/cls-news-kg.md §8）：
 * data worker 经 Spring AI structured output 反序列化的目标结构，随
 * KgExtractDonePayload.extraction 上行。关系/事件中的实体以 name 引用，
 * 锚点解析（stock/cls_subject 字典对齐）在 main 融合侧完成，worker 保持无状态。
 * 同时是 BeanOutputConverter 的 JSON schema 源——字段可空性由 prompt 规则兜底。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KgExtraction {

    private List<Entity> entities;

    private List<Relation> relations;

    private List<Event> events;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Entity {

        /** 规范名（去重锚点） */
        private String name;

        /** 实体类型：STOCK/SUBJECT/ORG/PERSON/PLACE/POLICY/EVENT/OTHER */
        private String type;

        /** 别名（全称/简称/英文缩写/上市主体名，供字典锚点匹配） */
        private List<String> aliases;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Relation {

        /** 主体实体名（须出现在 entities 中） */
        private String subjectName;

        /** 客体实体名（须出现在 entities 中） */
        private String objectName;

        /** 受控谓词：出台/发布/召开/签署/合作/任命/增长/下降/投资/扩大/禁止/推进/其他 */
        private String predicate;

        /** 置信度 0~1（模型自评，可空） */
        private Double confidence;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Event {

        /** 事件摘要句 */
        private String title;

        /** 补充细节（可空） */
        private String detail;

        /** 归一化事件时间（ISO-8601，无法确定时为空） */
        private String time;

        /** 原文时间表述（time 为空时必填） */
        private String timeText;

        /** 事件类型（一期不强制枚举，可空） */
        private String eventType;

        /** 参与实体名列表（须出现在 entities 中） */
        private List<String> entityNames;
    }
}
