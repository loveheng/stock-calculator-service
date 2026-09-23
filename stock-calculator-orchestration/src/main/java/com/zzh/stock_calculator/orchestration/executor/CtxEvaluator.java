package com.zzh.stock_calculator.orchestration.executor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一执行上下文 $ctx 求值器（agent-orchestration §八）：input_mapping 表达式强制基于
 * $ctx 根节点，三个命名空间杜绝二义与越界——
 * <ul>
 *   <li>$.params.*：task_instance.params（用户输入/填槽参数）；</li>
 *   <li>$.nodes.&lt;node_id&gt;.output.*：指定上游节点的执行输出（内存中完整对象）；</li>
 *   <li>$.env.*：系统变量（user_id / trace_id / now / timestamp）。</li>
 * </ul>
 * 仅支持点路径与数组下标取值，不做表达式运算（§十一 表达力上限：不引入脚本引擎）。
 */
public class CtxEvaluator {

    /** 内存执行上下文（节点间大输出仅内存流转，不经 Redis，§八） */
    public static final class Ctx {
        private final JsonNode params;
        private final Map<String, JsonNode> nodeOutputs = new LinkedHashMap<>();
        private final Map<String, String> env;

        public Ctx(JsonNode params, String userId, String traceId) {
            this(params, userId, traceId, Map.of());
        }

        /**
         * @param extraEnv 扩展系统变量（如 last_execution_time，§八 $.env 命名空间）；
         *                 与内置变量合并，键冲突以扩展值为准
         */
        public Ctx(JsonNode params, String userId, String traceId, Map<String, String> extraEnv) {
            this.params = params;
            ObjectMapper om = new ObjectMapper();
            ObjectNode envNode = om.createObjectNode();
            Map<String, String> envMap = new LinkedHashMap<>();
            envMap.put("user_id", userId);
            envMap.put("trace_id", traceId);
            envMap.put("timestamp", String.valueOf(System.currentTimeMillis()));
            envMap.put("now", java.time.LocalDateTime.now().toString());
            envMap.putAll(extraEnv);
            envMap.forEach((k, v) -> {
                envNode.put(k, v);
            });
            this.env = java.util.Collections.unmodifiableMap(envMap);
            // envNode 保留对象形态供 JSON 化；env 为字符串视图
        }

        public JsonNode getParams() { return params; }

        public JsonNode getNodeOutput(String nodeId) { return nodeOutputs.get(nodeId); }

        public void putNodeOutput(String nodeId, JsonNode output) { nodeOutputs.put(nodeId, output); }

        public Map<String, String> getEnv() { return env; }

        public JsonNode envAsJson() {
            ObjectMapper om = new ObjectMapper();
            ObjectNode n = om.createObjectNode();
            env.forEach(n::put);
            return n;
        }

        /** 重放入口：注入落库的上游节点输出（§八 失败节点重放，不重调上游） */
        public void restoreNodeOutputs(Map<String, JsonNode> restored) {
            nodeOutputs.putAll(restored);
        }
    }

    private static final ObjectMapper OM = new ObjectMapper();

    private CtxEvaluator() {}

    /**
     * 对 input_mapping 逐键求值：值形如 "$.xxx.y" 的取 $ctx 路径；字面量原样透传。
     * 返回求值后的参数对象（供节点调用）。
     */
    public static ObjectNode evaluate(JsonNode inputMapping, Ctx ctx) {
        ObjectNode out = OM.createObjectNode();
        if (inputMapping == null || !inputMapping.isObject()) {
            return out;
        }
        inputMapping.propertyNames().forEach(name -> {
            JsonNode v = inputMapping.get(name);
            if (v.isTextual() && v.asText().startsWith("$.")) {
                out.set(name, resolve(v.asText(), ctx));
            } else {
                out.set(name, v);
            }
        });
        return out;
    }

    /** 单表达式求值（switch 路由取值等场景）；非法前缀/越界抛 IllegalArgumentException */
    public static JsonNode resolve(String expression, Ctx ctx) {
        if (!expression.startsWith("$.")) {
            throw new IllegalArgumentException("表达式必须以 $. 开头（$ctx 寻址强制）: " + expression);
        }
        String[] segs = expression.substring(2).split("\\.");
        JsonNode cur;
        if (segs.length == 0) {
            throw new IllegalArgumentException("空表达式: " + expression);
        }
        cur = switch (segs[0]) {
            case "params" -> ctx.getParams();
            case "nodes" -> null; // 下一段是 node_id，单独处理
            case "env" -> ctx.envAsJson();
            default -> throw new IllegalArgumentException(
                    "越界命名空间（只准 params/nodes/env）: " + expression);
        };
        int i = 1;
        if ("nodes".equals(segs[0])) {
            if (segs.length < 3) {
                throw new IllegalArgumentException("nodes 取值须为 $.nodes.<node_id>.output...: " + expression);
            }
            JsonNode nodeOut = ctx.getNodeOutput(segs[1]);
            if (nodeOut == null) {
                throw new IllegalArgumentException("上游节点无输出: " + segs[1]);
            }
            // 跳过强制中间段 output（语义哨兵，防直接摸节点内部状态）
            if (!"output".equals(segs[2])) {
                throw new IllegalArgumentException("节点取值必须经 .output.: " + expression);
            }
            cur = nodeOut;
            i = 3;
        }
        for (; i < segs.length; i++) {
            String seg = segs[i];
            if (cur == null) {
                throw new IllegalArgumentException("路径中断: " + expression);
            }
            if (cur.isArray() && seg.matches("\\d+")) {
                int idx = Integer.parseInt(seg);
                if (idx >= cur.size()) {
                    throw new IllegalArgumentException("数组下标越界: " + expression);
                }
                cur = cur.get(idx);
            } else if (cur.isObject()) {
                cur = cur.path(seg);
                if (cur.isMissingNode()) {
                    throw new IllegalArgumentException("字段不存在: " + expression);
                }
            } else {
                throw new IllegalArgumentException("非容器节点继续寻址: " + expression);
            }
        }
        return cur;
    }

    /** 节点输出落库瘦身（§八 output_policy）：keep_summary / keep_head(N，默认 2KB) / keep_ref */
    public static JsonNode slimForStore(JsonNode output, String outputPolicy) {
        String policy = outputPolicy == null ? "keep_head" : outputPolicy;
        switch (policy) {
            case "keep_summary":
                ObjectNode s = OM.createObjectNode();
                s.put("summary", output.isTextual() ? head(output.asText(), 256)
                        : output.toString().length() <= 256 ? output.toString() : head(output.toString(), 256));
                return s;
            case "keep_ref":
                // 完整体落临时存储 + TTL 引用：初期简化为 head(8KB) 兜底（真引用随临时存储设施补）
                ObjectNode r = OM.createObjectNode();
                r.put("ref", "inline-tmp");
                r.put("head", head(output.toString(), 8192));
                return r;
            case "keep_head":
            default:
                ObjectNode h = OM.createObjectNode();
                h.put("head", head(output.toString(), 2048));
                return h;
        }
    }

    private static String head(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    /** foreach 展开辅助：数组输入拆子项 */
    public static List<JsonNode> toArrayItems(JsonNode array) {
        List<JsonNode> items = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(items::add);
        }
        return items;
    }
}
