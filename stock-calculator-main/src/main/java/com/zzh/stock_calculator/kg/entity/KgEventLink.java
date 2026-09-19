package com.zzh.stock_calculator.kg.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * kg_event_entity 事件-实体关联表（docs/ai-pipeline/cls-news-kg.md §5）：图查询入口
 * （实体 → 参与事件时间线）。UNIQUE(event_id, entity_id)——事件本身已按
 * (article_id, content_hash) 判重，关联随之幂等。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "kg_event_entity")
public class KgEventLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 事件 id（kg_event.id） */
    @Column(name = "event_id", nullable = false)
    private Long eventId;

    /** 实体 id（kg_entity.id） */
    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    /** 参与角色（一期未抽取，预留） */
    @Column(length = 30)
    private String role;
}
