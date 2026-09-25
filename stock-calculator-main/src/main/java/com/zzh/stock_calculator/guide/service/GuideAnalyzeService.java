package com.zzh.stock_calculator.guide.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.copilot.CopilotPromptResolver;
import com.zzh.stock_calculator.crawler.ClsArticleQueryApi;
import com.zzh.stock_calculator.crawler.ClsDictAnchorApi;
import com.zzh.stock_calculator.crawler.ClsDictAnchorApi.NamedAnchor;
import com.zzh.stock_calculator.crawler.ClsArticleQueryApi.ActiveStock;
import com.zzh.stock_calculator.crawler.StockDirectoryApi;
import com.zzh.stock_calculator.guide.dto.GuideDtos.AnalyzeMessageResponse;
import com.zzh.stock_calculator.guide.dto.GuideDtos.ArticleBrief;
import com.zzh.stock_calculator.guide.dto.GuideDtos.Candidate;
import com.zzh.stock_calculator.guide.dto.GuideDtos.EntityHit;
import com.zzh.stock_calculator.llm.LlmChainRouter;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 选股引导 Step1：消息→候选股票（docs/guide/design.md §三.1）。
 * <p>快路径：消息本身是实体型短查询（isEntityLikeQuery）→ 直接词典锚定，零 LLM 成本；
 * 慢路径：LlmChainRouter（免费链路）按 guide:entity_extract 契约抽实体名 → 逐名锚定。
 * LLM 未锚定的名字一律丢弃不编造（D4），回显为 keywords 供澄清；LLM 全链失败 fail-open
 * 回落词典路径并在响应标 llmDegraded（D8）。</p>
 * <p>响应 nextStep 首位（红线②）：自描述驱动多轮，keep_head 截断只丢尾部候选。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuideAnalyzeService {

    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 30;
    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final int MAX_CANDIDATES = 8;
    private static final int SAMPLE_ARTICLE_LIMIT = 2;
    private static final int FALLBACK_ARTICLE_LIMIT = 5;
    private static final int LLM_TIMEOUT_SECONDS = 20;
    private static final long SECONDS_PER_DAY = 86400L;

    /** 抽取契约标签（postgres/data.sql 播种，copilot_prompt_template 可热调；未播种回落常量） */
    public static final String TAG_GUIDE_ENTITY_EXTRACT = "guide:entity_extract";

    private static final String DEFAULT_EXTRACT_PROMPT = """
            你是 A 股选股引导助手。用户会给你一条他听到的消息，请从中抽取可能相关的公司名、\
            股票名、题材名或口语别称。只输出 JSON，格式：
            {"entities":["名称1","名称2"],"keywords":["关键词1"]}
            entities 放具体公司/股票/题材名称候选（允许别称与简称，词典会校验）；\
            keywords 放事件或行业关键词（候选为空时用于兜底搜索）。\
            无可靠候选时输出空数组。禁止输出 JSON 以外的任何内容。""";

    static final String NEXT_STEP_WITH_CANDIDATES =
            "向用户呈现候选清单（附近期提及数与样例依据）并请其选定关注对象；"
                    + "选定后调用 main.guide.stock_brief（stockId=所选，days 可透传）取个股档案。";
    static final String NEXT_STEP_EMPTY =
            "候选为空：向用户复述你理解的要点并澄清（请补充公司名/行业/大致时间），不要编造股票；"
                    + "如相关文章里有线索可先引用再追问。";

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final ClsDictAnchorApi clsDictAnchorApi;
    private final ClsArticleQueryApi clsArticleQueryApi;
    private final StockDirectoryApi stockDirectoryApi;
    private final CopilotPromptResolver copilotPromptResolver;
    private final LlmChainRouter llmChainRouter;

    private final ExecutorService llmExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** LLM 抽取结果（degraded=true 表示链路失败/降级，结果仅词典路径） */
    private record Extraction(List<String> entities, List<String> keywords, boolean degraded) {
    }

    /** 候选中间态（stockId 去重锚点，名称与依据后置批量填充） */
    private record CandidateSeed(String stockId, String hitType, String hitName) {
    }

    public AnalyzeMessageResponse analyze(String message, Integer days) {
        if (message == null || message.isBlank()) {
            throw new BusinessException(400, "消息内容不能为空");
        }
        String trimmed = message.trim();
        if (trimmed.length() > MAX_MESSAGE_LENGTH) {
            throw new BusinessException(400, "消息过长（≤" + MAX_MESSAGE_LENGTH + " 字），请精简转述");
        }
        int windowDays = clampDays(days);
        long sinceCtime = Instant.now().getEpochSecond() - windowDays * SECONDS_PER_DAY;

        Extraction extraction = extract(trimmed);
        List<CandidateSeed> seeds = anchorToSeeds(extraction.entities(), sinceCtime);
        List<Candidate> candidates = enrichCandidates(seeds, sinceCtime);

        List<EntityHit> entities = anchorsToEntities(clsDictAnchorApi.resolveEach(extraction.entities()));
        List<ArticleBrief> relatedArticles = candidates.isEmpty()
                ? fallbackArticles(extraction.keywords(), trimmed, sinceCtime)
                : List.of();
        String nextStep = candidates.isEmpty() ? NEXT_STEP_EMPTY : NEXT_STEP_WITH_CANDIDATES;
        log.info("guide analyze: days={}, entities={}, candidates={}, llmDegraded={}",
                windowDays, extraction.entities().size(), candidates.size(), extraction.degraded());
        return AnalyzeMessageResponse.builder()
                .nextStep(nextStep)
                .candidates(candidates)
                .entities(entities)
                .keywords(extraction.keywords())
                .relatedArticles(relatedArticles)
                .llmDegraded(extraction.degraded())
                .build();
    }

    // ==================== 实体抽取（快/慢路径） ====================

    private Extraction extract(String message) {
        if (clsArticleQueryApi.isEntityLikeQuery(message)) {
            // 快路径：消息即代码/公司名/题材名短查询，词典直锚零 LLM 成本
            return new Extraction(List.of(message), List.of(), false);
        }
        return extractByLlm(message);
    }

    private Extraction extractByLlm(String message) {
        String systemPrompt = copilotPromptResolver.resolveTaskTemplate(TAG_GUIDE_ENTITY_EXTRACT);
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = DEFAULT_EXTRACT_PROMPT;
        }
        try {
            String raw = chatWithTimeout(systemPrompt, message);
            if (llmChainRouter.isDegradedResponse(raw)) {
                log.warn("[DEGRADE] guide.entity_extract 降级模板响应，回落词典快路径");
                return new Extraction(List.of(), List.of(), true);
            }
            return parseExtraction(raw);
        } catch (BusinessException e) {
            log.warn("[DEGRADE] guide.entity_extract LLM 链路失败 code={}，回落词典快路径", e.getCode());
            return new Extraction(List.of(), List.of(), true);
        } catch (RuntimeException e) {
            log.warn("[DEGRADE] guide.entity_extract 输出解析失败，回落词典快路径", e);
            return new Extraction(List.of(), List.of(), true);
        }
    }

    private Extraction parseExtraction(String raw) {
        JsonNode node = JSON_MAPPER.readTree(raw);
        return new Extraction(stringList(node, "entities", 10),
                stringList(node, "keywords", 5), false);
    }

    private static List<String> stringList(JsonNode node, String field, int max) {
        JsonNode array = node.get(field);
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : array) {
            String value = item.asString();
            if (value != null && !value.isBlank()) {
                result.add(value.trim());
            }
            if (result.size() >= max) {
                break;
            }
        }
        return result;
    }

    /** 阻塞 chat 于虚拟线程 + 内部超时（照 CompositeSearchService 范式；语义异常原样透传） */
    private String chatWithTimeout(String systemPrompt, String userMessage) {
        Future<String> future = CompletableFuture.supplyAsync(
                () -> llmChainRouter.chat(systemPrompt, userMessage), llmExecutor);
        try {
            return future.get(LLM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new BusinessException(504, "选股引导实体抽取超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "选股引导被中断");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(503, "选股引导实体抽取失败");
        }
    }

    @PreDestroy
    void shutdownExecutor() {
        llmExecutor.shutdownNow();
    }

    // ==================== 锚定与候选组装 ====================

    /**
     * 逐名锚定转候选种子：STOCK 直锚在前，题材锚点经两跳扩展活跃股；
     * 未锚定名（anchorType=null）按 D4 丢弃——只有字典认得的名字才能成为候选。
     */
    private List<CandidateSeed> anchorToSeeds(List<String> entityNames, long sinceCtime) {
        List<ClsDictAnchorApi.NamedAnchor> anchors = clsDictAnchorApi.resolveEach(entityNames);
        List<CandidateSeed> seeds = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (NamedAnchor anchor : anchors) {
            if (anchor.anchorType() == null) {
                continue;
            }
            if (ClsDictAnchorApi.ANCHOR_STOCK.equals(anchor.anchorType())) {
                addSeed(seeds, seen, anchor.anchorId(), "STOCK", anchor.name());
            } else if (ClsDictAnchorApi.ANCHOR_CLS_SUBJECT.equals(anchor.anchorType())) {
                Long subjectId = parseLongSafe(anchor.anchorId());
                if (subjectId == null) {
                    continue;
                }
                List<ActiveStock> actives =
                        clsArticleQueryApi.activeStocksBySubject(subjectId, sinceCtime, MAX_CANDIDATES);
                for (ActiveStock active : actives) {
                    addSeed(seeds, seen, active.stockId(), "SUBJECT", anchor.name());
                }
            }
        }
        return seeds.size() > MAX_CANDIDATES ? seeds.subList(0, MAX_CANDIDATES) : seeds;
    }

    private static void addSeed(List<CandidateSeed> seeds, Set<String> seen,
                                String stockId, String hitType, String hitName) {
        if (stockId == null || stockId.isBlank() || !seen.add(stockId)) {
            return;
        }
        seeds.add(new CandidateSeed(stockId, hitType, hitName));
    }

    /** 批量回填名称与热度依据（提及计数 + 样例文章头） */
    private List<Candidate> enrichCandidates(List<CandidateSeed> seeds, long sinceCtime) {
        if (seeds.isEmpty()) {
            return List.of();
        }
        Set<String> stockIds = new LinkedHashSet<>();
        seeds.forEach(seed -> stockIds.add(seed.stockId()));
        Map<String, String> nameByCode = stockDirectoryApi.namesByCodes(stockIds);
        List<Candidate> candidates = new ArrayList<>();
        for (CandidateSeed seed : seeds) {
            List<ArticleBrief> samples = briefsFromHeads(
                    clsArticleQueryApi.recentArticlesByStock(seed.stockId(), sinceCtime, SAMPLE_ARTICLE_LIMIT));
            candidates.add(Candidate.builder()
                    .stockId(seed.stockId())
                    .stockName(nameByCode.getOrDefault(seed.stockId(), ""))
                    .hitType(seed.hitType())
                    .hitName(seed.hitName())
                    .recentMentionCount(clsArticleQueryApi.countByStockCodeSince(seed.stockId(), sinceCtime))
                    .sampleArticles(samples)
                    .build());
        }
        return candidates;
    }

    private List<EntityHit> anchorsToEntities(List<NamedAnchor> anchors) {
        List<EntityHit> result = new ArrayList<>();
        for (NamedAnchor anchor : anchors) {
            result.add(EntityHit.builder()
                    .name(anchor.name())
                    .anchorType(anchor.anchorType())
                    .anchorId(anchor.anchorId())
                    .build());
        }
        return result;
    }

    /** 空候选兜底：按 LLM keywords 逐词检索电报，首个命中即用；无 keywords 不硬搜（纯澄清） */
    private List<ArticleBrief> fallbackArticles(List<String> keywords, String message, long sinceCtime) {
        List<String> terms = new ArrayList<>(keywords);
        if (terms.isEmpty()) {
            return List.of();
        }
        for (String term : terms) {
            List<ClsArticleQueryApi.ArticleHit> hits =
                    clsArticleQueryApi.keywordSearch(term, sinceCtime, null, FALLBACK_ARTICLE_LIMIT);
            if (!hits.isEmpty()) {
                return briefsFromHits(hits);
            }
        }
        log.debug("guide fallback: keyword 无命中（{} 个词）", terms.size());
        return List.of();
    }

    private static List<ArticleBrief> briefsFromHits(List<ClsArticleQueryApi.ArticleHit> hits) {
        return hits.stream()
                .map(hit -> ArticleBrief.builder()
                        .articleId(hit.articleId())
                        .title(hit.title())
                        .ctime(hit.ctime())
                        .build())
                .toList();
    }

    private static List<ArticleBrief> briefsFromHeads(List<ClsArticleQueryApi.ArticleHead> heads) {
        return heads.stream()
                .map(head -> ArticleBrief.builder()
                        .articleId(head.articleId())
                        .title(head.title())
                        .ctime(head.ctime())
                        .build())
                .toList();
    }

    private static int clampDays(Integer days) {
        if (days == null) {
            return DEFAULT_DAYS;
        }
        return Math.min(Math.max(days, 1), MAX_DAYS);
    }

    private static Long parseLongSafe(String value) {
        try {
            return value == null ? null : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
