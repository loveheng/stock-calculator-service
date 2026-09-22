package com.zzh.stock_calculator.orchestration.tool;

import com.zzh.stock_calculator.orchestration.entity.ToolRegistryEntity;
import com.zzh.stock_calculator.orchestration.repository.ToolRegistryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表门面（agent-orchestration §6.1/步 2）：
 * - DB（tool_registry 表）是唯一事实源，内存缓存加速规划 prompt 组装；
 * - mcp 工具启动自注册（listTools 对照 :18081 现值 upsert，§6.1 登记方式）；
 * - main REST 接口白名单手工 SQL 登记（本模块不代管）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    /** kind=mcp 常量（tool_registry.kind 取值） */
    public static final String KIND_MCP = "mcp";
    public static final String KIND_REST = "rest";

    /** 输出落库策略取值（§6.1 output_policy） */
    public static final String OP_KEEP_SUMMARY = "keep_summary";
    public static final String OP_KEEP_HEAD = "keep_head";
    public static final String OP_KEEP_REF = "keep_ref";

    /** mcp 工具的领域 → 调用端点（:18081 经纪人，全部工具同端点，D5） */
    private static final String MCP_BROKER_ENDPOINT = "http://localhost:18081/sse";

    /** 启动自注册的 mcp 工具清单（name → {description, domain, params}） */
    private static final Map<String, McpSeed> MCP_SEEDS = Map.ofEntries(
            Map.entry("ping", new McpSeed("连通性自检：回显输入文本", "mq", "{}")),
            Map.entry("stock_analysis", new McpSeed("A股个股技术指标全家桶：MA/MACD/RSI/BOLL/KDJ 最新值与趋势信号，入参代码或名称", "quote",
                    "{\"stock\":{\"type\":\"string\",\"required\":true,\"desc\":\"股票代码或名称\"},"
                            + "\"days\":{\"type\":\"int\",\"required\":false,\"desc\":\"回看日线根数 30-500\"}}")),
            Map.entry("stock_daily", new McpSeed("个股日线序列（前复权 OHLCV），入参代码或名称", "quote",
                    "{\"stock\":{\"type\":\"string\",\"required\":true,\"desc\":\"股票代码或名称\"},"
                            + "\"days\":{\"type\":\"int\",\"required\":false,\"desc\":\"根数\"}}")),
            Map.entry("stock_levels", new McpSeed("个股支撑/压力位与关键价位", "quote",
                    "{\"stock\":{\"type\":\"string\",\"required\":true,\"desc\":\"股票代码或名称\"}}")),
            Map.entry("kb_search", new McpSeed("本地书库知识检索（RAG），按语义查经典/博主观点并返回出处", "kb",
                    "{\"query\":{\"type\":\"string\",\"required\":true,\"desc\":\"检索问题\"},"
                            + "\"book\":{\"type\":\"string\",\"required\":false,\"desc\":\"限定书名\"},"
                            + "\"top_k\":{\"type\":\"int\",\"required\":false,\"desc\":\"返回条数\"}}")),
            Map.entry("kb_book_list", new McpSeed("书库清单（书目元数据）", "kb", "{}")),
            Map.entry("kb_persona", new McpSeed("博主人格卡（语气/立场/金句 few-shot），数字人提示词层", "kb",
                    "{\"blogger\":{\"type\":\"string\",\"required\":true,\"desc\":\"博主名\"}}")));

    private final ToolRegistryRepository toolRegistryRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, ToolDescriptor> cache = new ConcurrentHashMap<>();

    /** 规划集（D7 白名单）：只吐 plannable 工具 */
    public List<ToolDescriptor> plannable() {
        return cache.values().stream().filter(ToolDescriptor::plannable).toList();
    }

    public ToolDescriptor get(String toolName) {
        return cache.get(toolName);
    }

    /** Executor 节点执行前版本漂移比对（§八）：schema 摘要不一致返回 false */
    public boolean schemaMatches(String toolName, JsonNode snapshotParamSchema) {
        ToolDescriptor current = cache.get(toolName);
        return current != null
                && current.getParamSchema() != null
                && current.getParamSchema().toString().equals(snapshotParamSchema == null ? "null" : snapshotParamSchema.toString());
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void registerMcpSeeds() {
        for (var e : MCP_SEEDS.entrySet()) {
            upsert(e.getKey(), KIND_MCP, MCP_BROKER_ENDPOINT, e.getValue().domain(),
                    e.getValue().description(), parseParams(e.getValue().params()));
        }
        reload();
    }

    /** upsert 单工具（登记/更新共用），随后调 reload() 刷缓存 */
    public void upsert(String toolName, String kind, String endpoint, String domain,
                       String description, JsonNode paramSchema) {
        ToolRegistryEntity entity = toolRegistryRepository.findById(toolName).orElse(null);
        if (entity == null) {
            entity = ToolRegistryEntity.builder()
                    .toolName(toolName).kind(kind).endpoint(endpoint)
                    .paramSchema(paramSchema).description(description).domain(domain)
                    .build();
        } else {
            entity.setKind(kind);
            entity.setEndpoint(endpoint);
            entity.setParamSchema(paramSchema);
            entity.setDescription(description);
            entity.setDomain(domain);
        }
        toolRegistryRepository.save(entity);
        log.info("[orchestration] tool_registry upsert: {} ({})", toolName, kind);
    }

    /** DB → 内存缓存（登记变更后调用） */
    public void reload() {
        cache.clear();
        for (ToolRegistryEntity e : toolRegistryRepository.findAll()) {
            cache.put(e.getToolName(), ToolDescriptor.builder()
                    .toolName(e.getToolName()).kind(e.getKind()).endpoint(e.getEndpoint())
                    .paramSchema(e.getParamSchema()).description(e.getDescription())
                    .domain(e.getDomain()).risk(e.getRisk()).outputPolicy(e.getOutputPolicy())
                    .enabled(Boolean.TRUE.equals(e.getEnabled()))
                    .build());
        }
        log.info("[orchestration] tool_registry 缓存装载 {} 个工具", cache.size());
    }

    private JsonNode parseParams(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (RuntimeException e) {
            return objectMapper.createObjectNode();
        }
    }

    /** 启动自注册种子（内聚记录） */
    private record McpSeed(String description, String domain, String params) {}
}
