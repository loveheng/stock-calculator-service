package com.zzh.stock_calculator.data.config;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.ClientOptions;
import com.openai.core.RequestOptions;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.openai.services.blocking.AdminService;
import com.openai.services.blocking.AudioService;
import com.openai.services.blocking.BatchService;
import com.openai.services.blocking.BetaService;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.CompletionService;
import com.openai.services.blocking.ContainerService;
import com.openai.services.blocking.ContentProvenanceCheckService;
import com.openai.services.blocking.ConversationService;
import com.openai.services.blocking.EmbeddingService;
import com.openai.services.blocking.EvalService;
import com.openai.services.blocking.FileService;
import com.openai.services.blocking.FineTuningService;
import com.openai.services.blocking.GraderService;
import com.openai.services.blocking.ImageService;
import com.openai.services.blocking.ModelService;
import com.openai.services.blocking.ModerationService;
import com.openai.services.blocking.RealtimeService;
import com.openai.services.blocking.ResponseService;
import com.openai.services.blocking.SkillService;
import com.openai.services.blocking.UploadService;
import com.openai.services.blocking.VectorStoreService;
import com.openai.services.blocking.VideoService;
import com.openai.services.blocking.WebhookService;

import java.util.function.Consumer;

/**
 * CF Workers AI 兼容垫片（worker 角色用）。与主服务
 * crawler.embedding.config.CfUsageFixingClient 同源复制（2026-09-11 阶段 3 任务 5）：
 * 两侧都直连 CF /ai/v1/embeddings，都需此修复；未并入 contract 模块是权衡结果——
 * 契约定位「纯 POJO 常量 + DTO」（D9），引入 openai-java 重依赖腐化其职责。
 * openai-java 升级新增接口方法时编译器强制两侧同步补齐转发，升级安全。
 *
 * <p>背景：CF 的 OpenAI 兼容端点 /ai/v1/embeddings 成功响应不含 usage 字段，
 * 而 openai-java 4.49.0 的 CreateEmbeddingResponse.usage() 实现为
 * getRequired("usage")——字段缺失必抛 OpenAIInvalidDataException，
 * 导致 Spring AI 2.0.1 官方 OpenAiEmbeddingModel 每次成功调用都在响应解析阶段失败。
 *
 * <p>方案：装饰 OpenAIClient，仅拦截 embeddings()，对缺失 usage 的成功响应
 * 回填合成 Usage(0,0)（worker 仅透传该值到 result.tokensUsed，不影响向量），
 * 其余方法一行转发给真实 client。
 */
public class CfUsageFixingClient implements OpenAIClient {

    private final OpenAIClient delegate;

    public CfUsageFixingClient(OpenAIClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public EmbeddingService embeddings() {
        return new FixingEmbeddingService(delegate.embeddings());
    }

    @Override
    public OpenAIClientAsync async() {
        return delegate.async();
    }

    @Override
    public WithRawResponse withRawResponse() {
        return delegate.withRawResponse();
    }

    @Override
    public OpenAIClient withOptions(Consumer<ClientOptions.Builder> modifier) {
        return delegate.withOptions(modifier);
    }

    @Override
    public CompletionService completions() {
        return delegate.completions();
    }

    @Override
    public ChatService chat() {
        return delegate.chat();
    }

    @Override
    public FileService files() {
        return delegate.files();
    }

    @Override
    public ImageService images() {
        return delegate.images();
    }

    @Override
    public ContentProvenanceCheckService contentProvenanceChecks() {
        return delegate.contentProvenanceChecks();
    }

    @Override
    public AudioService audio() {
        return delegate.audio();
    }

    @Override
    public ModerationService moderations() {
        return delegate.moderations();
    }

    @Override
    public ModelService models() {
        return delegate.models();
    }

    @Override
    public FineTuningService fineTuning() {
        return delegate.fineTuning();
    }

    @Override
    public GraderService graders() {
        return delegate.graders();
    }

    @Override
    public VectorStoreService vectorStores() {
        return delegate.vectorStores();
    }

    @Override
    public WebhookService webhooks() {
        return delegate.webhooks();
    }

    @Override
    public BetaService beta() {
        return delegate.beta();
    }

    @Override
    public BatchService batches() {
        return delegate.batches();
    }

    @Override
    public UploadService uploads() {
        return delegate.uploads();
    }

    @Override
    public AdminService admin() {
        return delegate.admin();
    }

    @Override
    public ResponseService responses() {
        return delegate.responses();
    }

    @Override
    public RealtimeService realtime() {
        return delegate.realtime();
    }

    @Override
    public ConversationService conversations() {
        return delegate.conversations();
    }

    @Override
    public EvalService evals() {
        return delegate.evals();
    }

    @Override
    public ContainerService containers() {
        return delegate.containers();
    }

    @Override
    public SkillService skills() {
        return delegate.skills();
    }

    @Override
    public VideoService videos() {
        return delegate.videos();
    }

    @Override
    public void close() {
        delegate.close();
    }

    /**
     * EmbeddingService 装饰器：create 成功后校验 usage 可解析（Spring AI 内部将无
     * 防护地调用 usage()），缺失时回填合成 Usage(0,0)。
     */
    static final class FixingEmbeddingService implements EmbeddingService {

        private final EmbeddingService delegate;

        FixingEmbeddingService(EmbeddingService delegate) {
            this.delegate = delegate;
        }

        @Override
        public EmbeddingService.WithRawResponse withRawResponse() {
            return delegate.withRawResponse();
        }

        @Override
        public EmbeddingService withOptions(Consumer<ClientOptions.Builder> modifier) {
            return new FixingEmbeddingService(delegate.withOptions(modifier));
        }

        @Override
        public CreateEmbeddingResponse create(EmbeddingCreateParams params, RequestOptions requestOptions) {
            CreateEmbeddingResponse response = delegate.create(params, requestOptions);
            try {
                response.usage();
            } catch (OpenAIInvalidDataException e) {
                response = response.toBuilder()
                        .usage(CreateEmbeddingResponse.Usage.builder()
                                .promptTokens(0L)
                                .totalTokens(0L)
                                .build())
                        .build();
            }
            return response;
        }
    }
}
