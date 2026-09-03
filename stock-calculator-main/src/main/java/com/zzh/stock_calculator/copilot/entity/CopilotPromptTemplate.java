package com.zzh.stock_calculator.copilot.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Copilot Prompt 模版实体（标签 → 提示词，DB 为唯一准源）。
 *
 * <p>yml 配置仅作首次种子：启动同步（CopilotPromptSync）只播种 DB 缺失的标签，
 * 之后经 /api/copilot/prompt/templates 接口在线增改删，重启不再被 yml 覆盖；
 * 启动时 DB 全量镜像至 Redis（{@code copilot:prompt:{tag}}），运行时解析器只读 Redis。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "copilot_prompt_template", uniqueConstraints = {
        @UniqueConstraint(name = "uq_copilot_prompt_template_tag", columnNames = {"tag"})
})
public class CopilotPromptTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 模版标签：focusBlockId（区块）/ scopeId / 页面段 / generic（通用兜底） */
    @Column(nullable = false, length = 100)
    private String tag;

    /** 提示词正文 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 创建时间（epoch millis） */
    @Column(nullable = false)
    private Long ctime;

    /** 最近更新时间（epoch millis） */
    @Column(nullable = false)
    private Long mtime;
}
