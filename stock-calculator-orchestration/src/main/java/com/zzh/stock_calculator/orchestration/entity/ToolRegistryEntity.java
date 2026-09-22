package com.zzh.stock_calculator.orchestration.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * 工具注册表（stock_mcp.tool_registry，agent-orchestration §6.1）：mcp 工具与 main REST
 * 接口统一描述（D5），规划 prompt 直接输入。登记：mcp 工具启动自注册，main 接口手工 SQL。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tool_registry", indexes = {
        @Index(name = "idx_tool_registry_domain", columnList = "domain")
})
public class ToolRegistryEntity {

    /** 全局唯一（stock_analysis / main.announcement.latest） */
    @Id
    @Column(name = "tool_name", length = 128)
    private String toolName;

    /** mcp / rest */
    @Column(nullable = false, length = 8)
    private String kind;

    /** mcp：:18081 调用端点；rest：URL + method */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String endpoint;

    /** 参数 schema（名称/类型/必填/枚举） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "JSONB")
    @Builder.Default
    private JsonNode paramSchema = new tools.jackson.databind.ObjectMapper().createArrayNode();

    /** 一句话语义（「什么时候该用我」，规划 prompt 的直接输入） */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    /** 领域标签（quote / kb / mq / announcement…），工具超 40 个后按域分组注入 */
    @Column(nullable = false, length = 32)
    private String domain;

    /** read / low / high；规划 prompt 硬约束只准编排 read/low（D7） */
    @Column(nullable = false, length = 8)
    @Builder.Default
    private String risk = "read";

    /** 输出落库策略：keep_summary / keep_head / keep_ref（防 node_states 膨胀，§八） */
    @Column(name = "output_policy", nullable = false, length = 16)
    @Builder.Default
    private String outputPolicy = "keep_head";

    /** 执行模式：sync（dispatch 直接代调秒回）/ async_long（转 create_task 走 Planner/Executor） */
    @Column(name = "execution_mode", nullable = false, length = 16)
    @Builder.Default
    private String executionMode = "sync";

    /** 下线开关 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "created_at", columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
    private LocalDateTime updatedAt;
}
