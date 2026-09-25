package com.zzh.llm;

import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * chat tier 注册表：按 tier 名惰性构造并缓存 {@link OpenAiChatModel}。
 * <p>刻意不做成动态 Bean 注册——tier 集合来自运行时配置，动态 BeanDefinition
 * 在 Spring AOT/native 下需构建期可见，会炸 native 构建（本项目教训）；
 * 惰性 Java 单例对 AOT 完全透明（纯直调代码，零反射）。</p>
 * <p>需要 @Qualifier 注入形态的服务，可自行声明薄 @Bean 委托本注册表。</p>
 */
public class LlmRegistry {

    private final LlmTierProperties props;

    private final Map<String, OpenAiChatModel> cache = new ConcurrentHashMap<>();

    public LlmRegistry(LlmTierProperties props) {
        this.props = props;
    }

    /** 已注册的 tier 名（配置顺序），用于报错提示与运维检查 */
    public Map<String, TierSpec> tiers() {
        return props.getTiers();
    }

    /**
     * 取 tier 对应的 chat 模型（惰性构造，线程安全）。
     * 三键缺失 fail-fast，报错附可用 tier 清单。
     */
    public OpenAiChatModel chatModel(String tier) {
        return cache.computeIfAbsent(tier, this::build);
    }

    /**
     * 运行时 options 工厂——spring-ai 2.0.1 三坑的唯一根治点：
     * ① 必须是 OpenAiChatOptions（createRequest 对 Prompt 运行时选项硬 cast）；
     * ② model 必须显式携带（缺省时 openai-java 以内置 gpt-5-mini 发出 → 渠道 404）；
     * ③ 采样参数只读运行时 options（不带则模型级 defaultOptions 的 temperature 被丢弃）。
     * 凡 Prompt 挂工具/挂 options 的调用点，一律经本工厂构造。
     */
    public OpenAiChatOptions runtimeOptions(String tier, ToolCallback... tools) {
        TierSpec spec = requireSpec(tier);
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
            .model(spec.getModel());
        if (spec.getTemperature() != null) {
            builder.temperature(spec.getTemperature());
        }
        if (spec.getMaxTokens() != null) {
            builder.maxTokens(spec.getMaxTokens());
        }
        if (tools != null && tools.length > 0) {
            builder.toolCallbacks(tools);
        }
        return builder.build();
    }

    /** tier 已配置且三键齐全（调用侧 readiness 门控，替代各服务自带的 enabled/hasText 检查） */
    public boolean isReady(String tier) {
        TierSpec spec = props.getTiers().get(tier);
        return spec != null
            && StringUtils.hasText(spec.getBaseUrl())
            && StringUtils.hasText(spec.getApiKey())
            && StringUtils.hasText(spec.getModel());
    }

    /** tier 配置的模型名（调用侧持久化/展示用，不触发模型构造） */
    public String model(String tier) {
        return requireSpec(tier).getModel();
    }

    /**
     * embedding tier 配置的模型名（fail-fast）。
     * 边界：chat 的 {@link #model} 走 ai.tiers.*，embedding 模型名在 ai.embeddings.*，
     * 两个命名空间互不覆盖——调用侧传 LlmTiers.EMBED 必须用本方法，否则 requireSpec
     * 会在 chat tiers 里找不到而误报「tier 未配置」（EmbeddingComputeWorker 教训）。
     */
    public String embedModel(String tier) {
        return embedSpec(tier).getModel();
    }

    /**
     * tier 原始规格（hand-rolled RestClient 型客户端读三键/参数用；
     * Spring AI 调用点请优先用 {@link #chatModel}/{@link #runtimeOptions}）。
     */
    public TierSpec spec(String tier) {
        return requireSpec(tier);
    }

    // ==================== embedding tier ====================

    /** embedding tier 凭据/model 是否齐备（按 provider 判定，运行期门控用） */
    public boolean isEmbedReady(String tier) {
        EmbedSpec s = props.getEmbeddings().get(tier);
        if (s == null || !StringUtils.hasText(s.getApiToken()) || !StringUtils.hasText(s.getModel())) {
            return false;
        }
        return "openai".equals(s.getProvider())
            ? StringUtils.hasText(s.getBaseUrl())
            : StringUtils.hasText(s.getAccountId());
    }

    /** embedding tier 原始规格（fail-fast） */
    public EmbedSpec embedSpec(String tier) {
        EmbedSpec spec = props.getEmbeddings().get(tier);
        if (spec == null) {
            throw new IllegalStateException("embedding tier 未配置: " + tier
                + "，已注册: " + props.getEmbeddings().keySet());
        }
        if (!isEmbedReady(tier)) {
            throw new IllegalStateException("embedding tier [" + tier + "] 配置不完整："
                + "provider=" + spec.getProvider() + " 要求的凭据/端点键缺失"
                + "（cloudflare 需 account-id，openai 需 base-url；api-token/model 恒必填）");
        }
        return spec;
    }

    /**
     * Spring AI {@link org.springframework.ai.openai.OpenAiEmbeddingModel}（main/data
     * PgVectorStore 依赖此形态）。provider=cloudflare 走 CF OpenAI 垫片（/ai/v1）并包
     * {@link CfUsageFixingClient} 修 usage 缺失；provider=openai 走通用兼容端点。
     * maxRetries 缺省 0（429 原样抛出交上层熔断分类）。
     */
    public org.springframework.ai.openai.OpenAiEmbeddingModel embeddingModel(String tier) {
        EmbedSpec s = embedSpec(tier);
        boolean cf = "cloudflare".equals(s.getProvider());
        String baseUrl = cf
            ? "https://api.cloudflare.com/client/v4/accounts/" + s.getAccountId() + "/ai/v1"
            : StringUtils.trimTrailingCharacter(s.getBaseUrl(), '/');
        int maxRetries = s.getMaxRetries() == null ? 0 : s.getMaxRetries();
        com.openai.client.OpenAIClient rawClient =
            org.springframework.ai.openai.setup.OpenAiSetup.setupSyncClient(
                baseUrl,
                s.getApiToken(),
                null, null, null, null, false, false,
                s.getModel(),
                s.getTimeout(),
                maxRetries,
                null, null,
                io.micrometer.observation.ObservationRegistry.NOOP,
                null, java.util.List.of());
        org.springframework.ai.openai.OpenAiEmbeddingModel.Builder builder =
            org.springframework.ai.openai.OpenAiEmbeddingModel.builder()
                .options(org.springframework.ai.openai.OpenAiEmbeddingOptions.builder()
                    .model(s.getModel())
                    .dimensions(s.getDimensions())
                    .timeout(s.effectiveTimeout())
                    .build());
        if (cf) {
            builder.openAiClient(new CfUsageFixingClient(rawClient));
        } else {
            builder.openAiClient(rawClient);
        }
        return builder.build();
    }

    /** 供应商无关 embedding 客户端（mcp/orchestration 等只要 float[] 的调用点），按 provider 装配并缓存 */
    public EmbeddingClient embedClient(String tier) {
        return embedCache.computeIfAbsent(tier, t -> {
            EmbedSpec s = embedSpec(t);
            return "openai".equals(s.getProvider())
                ? new OpenAiEmbeddingsClient(s)
                : new CfWorkersAiEmbeddingClient(s);
        });
    }

    private final Map<String, EmbeddingClient> embedCache = new ConcurrentHashMap<>();

    private OpenAiChatModel build(String tier) {
        TierSpec spec = requireSpec(tier);
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
            .model(spec.getModel())
            .baseUrl(StringUtils.trimTrailingCharacter(spec.getBaseUrl(), '/'))
            .apiKey(spec.getApiKey());
        if (spec.getTemperature() != null) {
            options.temperature(spec.getTemperature());
        }
        if (spec.getMaxTokens() != null) {
            options.maxTokens(spec.getMaxTokens());
        }
        if (spec.getTimeout() != null) {
            options.timeout(spec.getTimeout());
        }
        if (spec.getMaxRetries() != null) {
            options.maxRetries(spec.getMaxRetries());
        }
        return OpenAiChatModel.builder().options(options.build()).build();
    }

    private TierSpec requireSpec(String tier) {
        TierSpec spec = props.getTiers().get(tier);
        if (spec == null) {
            throw new IllegalStateException("LLM tier 未配置: " + tier
                + "，已注册 tier: " + props.getTiers().keySet());
        }
        if (!StringUtils.hasText(spec.getBaseUrl())
                || !StringUtils.hasText(spec.getApiKey())
                || !StringUtils.hasText(spec.getModel())) {
            throw new IllegalStateException("LLM tier [" + tier + "] 配置不完整："
                + "ai.tiers." + tier + ".{base-url,api-key,model} 三键缺一不可（"
                + "当前 base-url/model 是否有值见 yml，api-key 是否注入见环境变量）");
        }
        return spec;
    }
}
