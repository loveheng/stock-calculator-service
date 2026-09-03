package com.zzh.stock_calculator.copilot.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Copilot Prompt 模版修改历史（版本控制，append-only）。
 *
 * <p>每次经在线接口 upsert / delete 都自动留痕一条：rev 为该标签的修改序号
 * （从 1 递增，与 (tag, rev) 唯一约束对齐），content 记录本次操作后的内容
 * （DELETE 记录被删内容），operation ∈ {UPSERT, DELETE}。
 * data.sql 播种不记历史（默认值非修改）。回滚 = 取历史某条 content 再 PUT
 * （生成新 rev，审计链不断）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "copilot_prompt_template_history",
        indexes = {
                @Index(name = "idx_cpt_history_tag_ctime", columnList = "tag, ctime DESC")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_cpt_history_tag_rev", columnNames = {"tag", "rev"})
        })
public class CopilotPromptTemplateHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 模版标签（同主表 tag） */
    @Column(nullable = false, length = 100)
    private String tag;

    /** 修改序号：同一标签内从 1 递增 */
    @Column(nullable = false)
    private Integer rev;

    /** 本次操作后的提示词内容（DELETE 时记录被删内容） */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 操作类型：UPSERT | DELETE */
    @Column(nullable = false, length = 16)
    private String operation;

    /** 操作时间（epoch millis） */
    @Column(nullable = false)
    private Long ctime;
}
