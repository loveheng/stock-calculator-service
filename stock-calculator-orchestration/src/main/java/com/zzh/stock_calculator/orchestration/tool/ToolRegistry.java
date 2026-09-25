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

    /** 执行模式取值（步 5 分流地基）：sync 网关直接代调 / async_long 转 create_task */
    public static final String EM_SYNC = "sync";
    public static final String EM_ASYNC_LONG = "async_long";

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
            Map.entry("fetch_kline", new McpSeed("画布 K 线读穿代理：查库覆盖，不足自动拉取入库，返回升序日线切片（qfq 入库/raw 读穿）", "quote",
                    "{\"stock\":{\"type\":\"string\",\"required\":true,\"desc\":\"股票代码或名称\"},"
                            + "\"adjustType\":{\"type\":\"string\",\"required\":false,\"desc\":\"qfq（默认，入库）| raw（读穿不入库）\"},"
                            + "\"from\":{\"type\":\"string\",\"required\":false,\"desc\":\"起始日期 YYYY-MM-DD\"},"
                            + "\"to\":{\"type\":\"string\",\"required\":false,\"desc\":\"截止日期 YYYY-MM-DD，默认最新\"},"
                            + "\"snapshotId\":{\"type\":\"string\",\"required\":false,\"desc\":\"调用方追踪 ID\"}}")),
            Map.entry("compute_indicators", new McpSeed("画布无状态指标计算：对给定 K 线切片逐根算 macd/kdj/boll 序列，纯计算无外部 IO", "quote",
                    "{\"klines\":{\"type\":\"string\",\"required\":true,\"desc\":\"K 线切片 JSON 数组（date/open/close/high/low/volume，升序 ≤120）\"},"
                            + "\"indicators\":{\"type\":\"string\",\"required\":true,\"desc\":\"指标名 JSON 数组，如 [macd,kdj,boll]\"}}")),
            Map.entry("stock_levels", new McpSeed("个股支撑/压力位与关键价位", "quote",
                    "{\"stock\":{\"type\":\"string\",\"required\":true,\"desc\":\"股票代码或名称\"}}")),
            Map.entry("stock_radar_check", new McpSeed("多条件雷达断言：是否突破N日均线和/或量能放大，返回枚举信号 both/break_only/volume_only/none（switch 节点按此分流，命中才触发提醒）", "quote",
                    "{\"stock\":{\"type\":\"string\",\"required\":true,\"desc\":\"股票代码或名称\"},"
                            + "\"maWindow\":{\"type\":\"int\",\"required\":false,\"desc\":\"均线窗口（日），默认20，范围5-120\"},"
                            + "\"volumeRatio\":{\"type\":\"number\",\"required\":false,\"desc\":\"量能倍数阈值，默认2.0\"}}")),
            Map.entry("kb_search", new McpSeed("本地书库知识检索（RAG），按语义查经典/博主观点并返回出处", "kb",
                    "{\"query\":{\"type\":\"string\",\"required\":true,\"desc\":\"检索问题\"},"
                            + "\"book\":{\"type\":\"string\",\"required\":false,\"desc\":\"限定书名\"},"
                            + "\"top_k\":{\"type\":\"int\",\"required\":false,\"desc\":\"返回条数\"}}")),
            Map.entry("kb_book_list", new McpSeed("书库清单（书目元数据）", "kb", "{}")),
            Map.entry("kb_persona", new McpSeed("博主人格卡（语气/立场/金句 few-shot），数字人提示词层", "kb",
                    "{\"blogger\":{\"type\":\"string\",\"required\":true,\"desc\":\"博主名\"}}")));

    /**
     * main REST 工具种子（2② 财报对比链路数据源；原「手工 SQL 登记」收编为同款启动自注册，
     * upsert 幂等不覆盖人工改过的 risk/output_policy 之外的列——人工调整请直接改 DB 后 reload）。
     */
    private static final List<RestSeed> REST_SEEDS = List.of(
            new RestSeed("main.announcement.summaries", "GET /api/announcement/summaries",
                    "announcement",
                    "按公告 ID 集合批量取蒸馏摘要（财报/公告对比的数据源；配合 foreach 逐份抽取+LLM 汇总）",
                    "{\"ids\":{\"type\":\"array\",\"required\":true,\"desc\":\"CNINFO announcementId 集合，1-20 个\"}}",
                    "keep_summary", EM_SYNC),
            new RestSeed("main.guide.analyze_message", "POST /api/guide/analyze-message",
                    "guide",
                    "选股引导 Step1：从用户听到的一条消息找相关股票（词典快路径+LLM 抽实体，返回候选清单+依据+nextStep 指令）。"
                            + "用户表达「听到一个消息/新闻，想看看有什么股票机会」时先调本工具，拿到候选后向用户澄清选定",
                    "{\"message\":{\"type\":\"string\",\"required\":true,\"desc\":\"消息原文（≤500 字口语转述）\"},"
                            + "\"days\":{\"type\":\"int\",\"required\":false,\"desc\":\"时间窗（天），默认 7，范围 1-30\"}}",
                    "keep_head", EM_SYNC),
            new RestSeed("main.guide.stock_brief", "GET /api/guide/stock-brief",
                    "guide",
                    "选股引导 Step2：个股引导档案（近期电报提及+题材归属+公告摘要+nextSteps 建议）。"
                            + "用户从 analyze_message 候选中选定某只后调用，stockId 必须用 Step1 返回的 stockId",
                    "{\"stockId\":{\"type\":\"string\",\"required\":true,\"desc\":\"股票 ID（Step1 候选的 stockId）\"},"
                            + "\"days\":{\"type\":\"int\",\"required\":false,\"desc\":\"时间窗（天），默认 7\"}}",
                    "keep_head", EM_SYNC));

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
        for (RestSeed seed : REST_SEEDS) {
            upsert(seed.name(), KIND_REST, seed.endpoint(), seed.domain(),
                    seed.description(), parseParams(seed.params()), seed.executionMode());
            // REST 种子按列 upsert 不触 risk/outputPolicy（upsert 未覆盖这两列，人工值安全）；
            // output_policy/risk 有内置默认（DB 列 default），首次插入即生效
        }
        reload();
    }

    /** upsert 单工具（登记/更新共用），随后调 reload() 刷缓存 */
    public void upsert(String toolName, String kind, String endpoint, String domain,
                       String description, JsonNode paramSchema) {
        upsert(toolName, kind, endpoint, domain, description, paramSchema, EM_SYNC);
    }

    /** upsert 单工具（带执行模式），随后调 reload() 刷缓存 */
    public void upsert(String toolName, String kind, String endpoint, String domain,
                       String description, JsonNode paramSchema, String executionMode) {
        ToolRegistryEntity entity = toolRegistryRepository.findById(toolName).orElse(null);
        if (entity == null) {
            entity = ToolRegistryEntity.builder()
                    .toolName(toolName).kind(kind).endpoint(endpoint)
                    .paramSchema(paramSchema).description(description).domain(domain)
                    .executionMode(executionMode)
                    .build();
        } else {
            entity.setKind(kind);
            entity.setEndpoint(endpoint);
            entity.setParamSchema(paramSchema);
            entity.setDescription(description);
            entity.setDomain(domain);
            entity.setExecutionMode(executionMode);
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
                    .executionMode(e.getExecutionMode())
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

    /** REST 种子（main 只读接口白名单，启动自注册）：endpoint 形如 "GET /api/..." */
    private record RestSeed(String name, String endpoint, String domain,
                            String description, String params, String outputPolicy,
                            String executionMode) {}
}
