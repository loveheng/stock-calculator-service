package com.zzh.stock_calculator.orchestration.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * 工具描述符（agent-orchestration §6.1 内存视图）：规划 prompt 注入与 Executor 调用共用。
 * 统一工具面（D5）：mcp 工具与 main REST 接口经同一 registry 描述，规划器不感知差异。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDescriptor {

    /** 全局唯一（stock_analysis / main.announcement.latest） */
    private String toolName;

    /** mcp / rest */
    private String kind;

    /** mcp：:18081 调用端点；rest：URL + method（"GET /api/..."） */
    private String endpoint;

    /** 参数 schema（名称/类型/必填/枚举） */
    private JsonNode paramSchema;

    /** 一句话语义（「什么时候该用我」） */
    private String description;

    /** 领域标签（quote / kb / mq / announcement…） */
    private String domain;

    /** read / low / high（D7：规划集只收 read/low） */
    private String risk;

    /** keep_summary / keep_head / keep_ref（node_states 落库瘦身策略） */
    private String outputPolicy;

    /** sync（dispatch 直接代调）/ async_long（转 create_task 走 Planner/Executor） */
    private String executionMode;

    /** 是否可参与规划与执行 */
    private boolean enabled;

    /** D7 白名单：可编排 = 启用且 risk 非 high */
    public boolean plannable() {
        return enabled && !"high".equals(risk);
    }
}
