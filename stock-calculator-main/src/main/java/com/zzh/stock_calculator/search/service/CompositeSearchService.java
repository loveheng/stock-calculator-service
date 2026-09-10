package com.zzh.stock_calculator.search.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.llm.LlmChainRouter;
import com.zzh.stock_calculator.search.config.SearchProperties;
import com.zzh.stock_calculator.search.dto.SearchDtos.AnnouncementItem;
import com.zzh.stock_calculator.search.dto.SearchDtos.AnnouncementSearchResponse;
import com.zzh.stock_calculator.search.dto.SearchDtos.Citation;
import com.zzh.stock_calculator.search.dto.SearchDtos.ClsItem;
import com.zzh.stock_calculator.search.dto.SearchDtos.ClsSearchResponse;
import com.zzh.stock_calculator.search.dto.SearchDtos.CompositeResponse;
import com.zzh.stock_calculator.search.dto.SearchDtos.DoneEvent;
import com.zzh.stock_calculator.search.dto.SearchDtos.DeltaEvent;
import com.zzh.stock_calculator.search.dto.SearchDtos.MetaEvent;
import com.zzh.stock_calculator.search.dto.SearchDtos.SseErrorEvent;
import com.zzh.stock_calculator.search.util.SearchParamsValidator.DateRange;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 综合摘要（backend-implementation §4，api 文档 §4；拍板 C9/C10）：
 * 双库检索（复用 §2/§3 服务，topK 各 composite.retrieval-top-k）→ 引用/材料组装 → LLM 生成。
 * <ul>
 *   <li>JSON 阻塞路径（方案 B）：{@link #composite}，内部超时 llm-timeout-seconds，超时 504 信封；</li>
 *   <li>SSE 流式路径（方案 A）：{@link #compositeStream}，事件序 meta（先于 delta，引用先上屏）
 *       → delta×N → done(200,"ok") → complete；LLM 异常/降级 → error 事件后关流。
 *       骨架仿 copilot askStream（SseEmitter timeout = LLM 预算 +5s，勿抄 copilot 300s）。</li>
 * </ul>
 * LLM 渠道【拍板 C10】复用 {@link LlmChainRouter} 既有免费链；链路只有阻塞 chat()
 * → 虚拟线程执行 + 结果分块模拟 delta。阶段一同步完成（含双库检索与空态判定，
 * 双库命中 0 不调 LLM，直接固定话术空态 code 200）。
 * <p>红线：材料只进 Prompt，不落库不打日志；本类日志只打条数/耗时/code，
 * 不打 query/stockCodes/摘要明文（C1）。降级模板经 isDegradedResponse 识别，
 * 不作为 summary 下发（llm 基包契约：不可写入结果/不含业务内容）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompositeSearchService {

    private static final String EMPTY_SUMMARY = "未检索到足够相关信息";
    private static final String DEGRADED_MESSAGE = "综合摘要服务暂不可用，请稍后重试";
    private static final String SYSTEM_PROMPT = """
            你是股票资讯助手。只允许基于用户消息中的编号材料作答，严禁引入任何外部知识或自行推测；
            若材料不足以回答，只输出固定话术：未检索到足够相关信息。
            摘要用中文，2~3 句，客观陈述并自然衔接各材料，不要输出引用编号，不要寒暄。""";
    /** 模拟 delta 的分块大小（字符） */
    private static final int DELTA_CHUNK_CHARS = 80;

    private final AnnouncementSearchService announcementSearchService;
    private final ClsSearchService clsSearchService;
    private final LlmChainRouter llmChainRouter;
    private final SearchProperties properties;

    /** 虚拟线程执行器：阻塞式 LLM 调用不占平台线程；JVM 退出由 @PreDestroy 收口 */
    private final ExecutorService llmExecutor = Executors.newVirtualThreadPerTaskExecutor();

    // ==================== 方案 B：JSON 阻塞路径 ====================

    /** 综合摘要（阻塞）；LLM 超时 → 504，全链失败 → 503（交全局 handler 出信封） */
    public CompositeResponse composite(String query, List<String> stockCodes, DateRange dateRange) {
        RetrievalResult retrieval = retrieve(query, stockCodes, dateRange);
        if (retrieval.citations().isEmpty()) {
            return CompositeResponse.builder().summary(EMPTY_SUMMARY).citations(List.of()).build();
        }
        String summary = chatWithTimeout(SYSTEM_PROMPT, buildUserPrompt(query, retrieval.materials()));
        if (llmChainRouter.isDegradedResponse(summary)) {
            throw new BusinessException(503, DEGRADED_MESSAGE);
        }
        return CompositeResponse.builder()
                .summary(summary.trim())
                .citations(retrieval.citations())
                .build();
    }

    // ==================== 方案 A：SSE 流式路径 ====================

    /**
     * 阶段一（双库检索）在调用方线程同步完成，失败直接抛（控制器 JSON 信封回落，此时尚未开流）；
     * 返回 emitter 后由虚拟线程执行阶段二（meta → delta×N → done → complete）。
     * 早期 send 会被 ResponseBodyEmitter 缓冲至 handler 初始化（spring-webmvc 7.0.9 实证）。
     */
    public SseEmitter compositeStream(String query, List<String> stockCodes, DateRange dateRange) {
        RetrievalResult retrieval = retrieve(query, stockCodes, dateRange);
        SseEmitter emitter = new SseEmitter(sseTimeoutMs());
        Thread.ofVirtual().start(() -> runStream(emitter, retrieval, query));
        return emitter;
    }

    private void runStream(SseEmitter emitter, RetrievalResult retrieval, String query) {
        try {
            send(emitter, SseEmitter.event().name("meta")
                    .data(new MetaEvent(retrieval.citations()), MediaType.APPLICATION_JSON));
            if (retrieval.citations().isEmpty()) {
                // 双库命中 0：不调 LLM，固定话术空态（api 文档 §4 LLM 约束）
                send(emitter, SseEmitter.event().name("delta")
                        .data(new DeltaEvent(EMPTY_SUMMARY), MediaType.APPLICATION_JSON));
                completeWithDone(emitter);
                return;
            }
            String summary;
            try {
                summary = chatWithTimeout(SYSTEM_PROMPT, buildUserPrompt(query, retrieval.materials()));
                if (llmChainRouter.isDegradedResponse(summary)) {
                    throw new BusinessException(503, DEGRADED_MESSAGE);
                }
            } catch (BusinessException e) {
                log.warn("composite stream llm failed: code={}", e.getCode());
                send(emitter, SseEmitter.event().name("error")
                        .data(new SseErrorEvent(e.getCode(), e.getMessage(), null),
                                MediaType.APPLICATION_JSON));
                emitter.complete();
                return;
            }
            for (String chunk : chunks(summary.trim())) {
                if (!send(emitter, SseEmitter.event().name("delta")
                        .data(new DeltaEvent(chunk), MediaType.APPLICATION_JSON))) {
                    emitter.complete(); // 客户端已断开，中止后续发送
                    return;
                }
            }
            completeWithDone(emitter);
        } catch (Exception e) {
            // 兜底：worker 线程内异常无法交给全局 handler，尝试 error 事件后关流
            log.error("composite stream unexpected error", e);
            send(emitter, SseEmitter.event().name("error")
                    .data(new SseErrorEvent(503, "服务器内部错误", null), MediaType.APPLICATION_JSON));
            emitter.complete();
        }
    }

    private void completeWithDone(SseEmitter emitter) {
        send(emitter, SseEmitter.event().name("done")
                .data(new DoneEvent(200, "ok"), MediaType.APPLICATION_JSON));
        emitter.complete(); // 发完 done 必须关闭流
    }

    // ==================== 检索与组装 ====================

    /** 双库检索 + 引用/材料组装（材料仅供 Prompt，禁止打日志/落库） */
    private RetrievalResult retrieve(String query, List<String> stockCodes, DateRange dateRange) {
        int topK = properties.getComposite().getRetrievalTopK();
        AnnouncementSearchResponse announcements =
                announcementSearchService.search(query, stockCodes, dateRange, topK);
        ClsSearchResponse cls = clsSearchService.search(query, dateRange, topK);
        List<Citation> citations = new ArrayList<>();
        List<String> materials = new ArrayList<>();
        int index = 1;
        for (AnnouncementItem item : announcements.getItems()) {
            citations.add(Citation.builder().kind("announcement").resultId(item.getResultId())
                    .stockId(item.getStockId()).date(item.getAnnDate()).title(item.getTitle()).build());
            materials.add(material(index++, "公告", item.getStockId(), item.getAnnDate(),
                    item.getTitle(), item.getSummary()));
        }
        for (ClsItem item : cls.getItems()) {
            String stockId = firstMentionStockId(item);
            citations.add(Citation.builder().kind("cls").resultId(item.getResultId())
                    .stockId(stockId).date(dateOf(item.getPublishedAt())).title(item.getTitle()).build());
            materials.add(material(index++, "电报", stockId, dateOf(item.getPublishedAt()),
                    item.getTitle(), item.getSummary()));
        }
        log.info("composite retrieval done: announcements={}, cls={}",
                announcements.getItems().size(), cls.getItems().size());
        return new RetrievalResult(List.copyOf(citations), List.copyOf(materials));
    }

    /** 编号材料行：[序号] (类型 | 股票 | 日期) 标题：摘要 */
    private static String material(int index, String kind, String stockId, String date,
                                   String title, String summary) {
        return "[%d] (%s%s%s) %s：%s".formatted(index, kind,
                stockId == null || stockId.isBlank() ? "" : " | 股票:" + stockId,
                date == null || date.isBlank() ? "" : " | 日期:" + date,
                title == null ? "" : title,
                summary == null ? "" : summary);
    }

    private static String buildUserPrompt(String query, List<String> materials) {
        StringBuilder sb = new StringBuilder("材料列表：\n");
        materials.forEach(item -> sb.append(item).append('\n'));
        sb.append('\n').append("用户问题：").append(query);
        return sb.toString();
    }

    private static String firstMentionStockId(ClsItem item) {
        return item.getMentions() == null || item.getMentions().isEmpty()
                ? null : item.getMentions().get(0).getStockId();
    }

    /** cls publishedAt（yyyy-MM-dd HH:mm）取日期部分；异常形状原样返回 */
    private static String dateOf(String publishedAt) {
        return publishedAt == null || publishedAt.length() < 10
                ? publishedAt : publishedAt.substring(0, 10);
    }

    // ==================== LLM 调用与工具 ====================

    /** 阻塞 chat 于虚拟线程 + 内部超时（api 文档 §4：后端内部超时 30s，超时 504） */
    private String chatWithTimeout(String systemPrompt, String userMessage) {
        int timeoutSeconds = properties.getComposite().getLlmTimeoutSeconds();
        Future<String> future = CompletableFuture.supplyAsync(
                () -> llmChainRouter.chat(systemPrompt, userMessage), llmExecutor);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new BusinessException(504, "综合摘要超时，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "综合摘要被中断");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof BusinessException businessException) {
                throw businessException; // 路由器语义（400/503）原样透传
            }
            throw new BusinessException(503, "综合摘要生成失败，请稍后重试");
        }
    }

    private long sseTimeoutMs() {
        return (properties.getComposite().getLlmTimeoutSeconds() + 5L) * 1000L;
    }

    /** 全文分块为模拟 delta（无人工延时，交 TCP 批量到达） */
    private static List<String> chunks(String text) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < text.length(); i += DELTA_CHUNK_CHARS) {
            parts.add(text.substring(i, Math.min(text.length(), i + DELTA_CHUNK_CHARS)));
        }
        return parts.isEmpty() ? List.of(text) : parts;
    }

    /** 发送 SSE 事件；客户端断开/流已关闭返回 false（调用方据此中止后续发送） */
    private static boolean send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @PreDestroy
    void shutdownExecutor() {
        llmExecutor.shutdownNow();
    }

    /** 双库检索产物：citations 随 meta/响应下发，materials 仅进 Prompt */
    private record RetrievalResult(List<Citation> citations, List<String> materials) {
    }
}
