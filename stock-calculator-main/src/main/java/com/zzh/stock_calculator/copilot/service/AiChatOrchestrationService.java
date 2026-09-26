package com.zzh.stock_calculator.copilot.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.copilot.CopilotPromptResolver;
import com.zzh.stock_calculator.copilot.dto.CopilotDtos.AskRequest;
import com.zzh.stock_calculator.copilot.dto.CopilotDtos.AskResponse;
import com.zzh.stock_calculator.copilot.dto.CopilotDtos.CopilotActionItem;
import com.zzh.stock_calculator.copilot.dto.CopilotDtos.DeltaEvent;
import com.zzh.stock_calculator.copilot.dto.CopilotDtos.ErrorEvent;
import com.zzh.stock_calculator.copilot.entity.AiChatMessage;
import com.zzh.stock_calculator.copilot.entity.AiChatSession;
import com.zzh.stock_calculator.copilot.repository.AiChatMessageRepository;
import com.zzh.stock_calculator.copilot.repository.AiChatSessionRepository;
import com.zzh.stock_calculator.copilot.service.store.AiChatSessionStore;
import com.zzh.stock_calculator.copilot.util.ActionShellStreamFilter;
import com.zzh.stock_calculator.copilot.util.CopilotStatActionExtractor;
import com.zzh.stock_calculator.copilot.util.CopilotTaskPromptRenderer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

/**
 * Copilot AI 聊天编排服务（事务驱动）。
 * <p>流程：限流 → 幂等两段式 → 会话 CRUD → 消息持久化 → 滑动窗口 → LLM 调用 → 归档回复</p>
 */
@Slf4j
@Service
public class AiChatOrchestrationService {

    private final AiChatSessionStore sessionStore;
    private final AiChatMessageRepository messageRepository;
    private final AiChatSessionRepository sessionRepository;
    private final CopilotPromptResolver promptResolver;
    private final com.zzh.stock_calculator.copilot.util.AiChatRateLimiter rateLimiter;
    private final PlatformTransactionManager txnMgr;
    private final com.zzh.stock_calculator.copilot.service.CopilotMemoryService memoryService;
    private final com.zzh.stock_calculator.copilot.service.CopilotMemoryRecallService memoryRecall;
    private final com.zzh.stock_calculator.copilot.service.PersonaPromptInjectionService personaInjection;
    /** MCP 工具池（:18083 orchestration dispatch 单连接，spring.ai.mcp.client 自动装配）；
     *  ObjectProvider 容错——MCP_CLIENT_ENABLED=false 或服务未起时不挂工具，聊天不阻塞 */
    private final ObjectProvider<org.springframework.ai.tool.ToolCallbackProvider>
        mcpToolCallbacksProvider;
    /** LLM tier 注册表（stock-calculator-llm）：chat 模型与运行时 options 统一经此获取，
     *  封死 spring-ai 2.0.1 运行时 options 三坑（cast/缺 model/采样参数丢失） */
    private final com.zzh.llm.LlmRegistry llmRegistry;

    /**
     * 显式构造器（项目无 lombok.config，@RequiredArgsConstructor 不会复制 @Qualifier）。
     * 模型走 LlmRegistry（ai.tiers.openai-max），不经 LlmChainRouter
     * （问答付费渠道与引流免费渠道隔离的架构决策不变，仅装配设施换轨）。
     */
    public AiChatOrchestrationService(
        AiChatSessionStore sessionStore,
        AiChatMessageRepository messageRepository,
        AiChatSessionRepository sessionRepository,
        CopilotPromptResolver promptResolver,
        com.zzh.stock_calculator.copilot.util.AiChatRateLimiter rateLimiter,
        PlatformTransactionManager txnMgr,
        com.zzh.stock_calculator.copilot.service.CopilotMemoryService memoryService,
        com.zzh.stock_calculator.copilot.service.CopilotMemoryRecallService memoryRecall,
        com.zzh.stock_calculator.copilot.service.PersonaPromptInjectionService personaInjection,
        ObjectProvider<org.springframework.ai.tool.ToolCallbackProvider> mcpToolCallbacksProvider,
        com.zzh.llm.LlmRegistry llmRegistry
    ) {
        this.sessionStore = sessionStore;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.promptResolver = promptResolver;
        this.rateLimiter = rateLimiter;
        this.txnMgr = txnMgr;
        this.memoryService = memoryService;
        this.memoryRecall = memoryRecall;
        this.personaInjection = personaInjection;
        this.mcpToolCallbacksProvider = mcpToolCallbacksProvider;
        this.llmRegistry = llmRegistry;
    }

    /** LLM 超时窗口（秒）：pending 状态下同一 cid 在窗内视为「请求正在处理中」 */
    private static final int PENDING_WINDOW_SECONDS = 60;

    /** SSE 响应超时（ms）：与 LLM 客户端 callTimeout=300s 对齐，避免容器默认 30s 掐断长流 */
    private static final long SSE_TIMEOUT_MS = 300_000L;

    /** promptHints 硬顶（free-canvas §2.8 不可信输入防线）：UTF-8 8192 字节，超长截断不报错 */
    private static final int PROMPT_HINTS_MAX_BYTES = 8192;

    /**
     * 选股引导约定（docs/guide/design.md D12，G2 旁路回流）：全聊天面统一注入的固定短段——
     * 用户旁路进入（直接问个股/题材）时 copilot 主动带上引导视角；工具名与 orchestration
     * REST_SEEDS、会话身份块（reminder_*）同款代码级耦合先例。置于动作块契约之前（外壳协议保持最末）。
     */
    private static final String GUIDE_FLOW_CONVENTION =
        "\n\n【选股引导约定】\n"
            + "用户听到消息/传闻想选股、给题材或股票代码问机会时，先调用 main.guide.analyze_message "
            + "获取候选清单并请用户选定；深入讨论某只个股时，可调用 main.guide.stock_brief 补充近况档案"
            + "（电报提及、题材归属、公告摘要）后再回答；纯技术面问题照常走形态/指标工具。";

    // ==================== Ask（主方法）====================

    /**
     * 处理用户提问（JSON 阻塞路径）：阶段一（beginAsk）→ LLM 调用（事务外）→ 阶段二归档。
     *
     * 架构决策（v1.5.1）：userMsg.save 和 LLM 调用分离——
     * userMsg 先提交到 DB（status='pending'），LLM 调用在事务外执行（不占 DB 连接），
     * 成功后新事务写 assistantMsg，失败后独立事务回滚 userMsg.status→failed。
     * SSE 流式路径见 {@link #askStream}，两条路径共用阶段一。
     */
    public AskResponse ask(String userId, String scopeId, AskRequest req) {
        PendingAsk pending = beginAsk(userId, scopeId, req);
        ChatResponse response;
        try {
            response = callLlm(pending.prompt());
        } catch (BusinessException e) {
            // LLM 失败：独立事务更新 userMsg.status→failed（供重试识别）
            markUserMessageFailed(pending.userMsg().getId());
            throw e;
        }
        // 动作块容错提取（无块/解析失败 = fail-open，原文归档）：
        // 权威全文剔除动作块后归档，actions 仅随响应下发（不落库不打日志）
        String rawText = textOf(response);
        CopilotStatActionExtractor.Parsed output =
            CopilotStatActionExtractor.parse(rawText);
        logActionBlockAnomaly(rawText, output);
        return persistAssistant(
            userId,
            pending.userMsg(),
            output != null ? output.cleanedText() : rawText,
            usageOf(response),
            output == null ? null : output.actions()
        );
    }

    /**
     * SSE 流式提问：与 {@link #ask} 共用阶段一（beginAsk），失败抛 BusinessException
     * 由控制器回落 JSON 信封（此时尚未开始流式响应，可安全回落）；
     * 阶段二改为订阅 LLM 流：delta 逐 chunk 透传 → done 携带归档后的权威全文 → complete 关流。
     * 流中 LLM 异常/归档失败/客户端断开/超时：标记 userMsg.status→failed（前端按可重发处理，
     * 断开后同 cid 可立即重发续跑，不必等 pending 窗口 60s）+ error 事件后关流。
     * 红线不变：LLM 与 DB 事务解耦，contextSummary 只进 Prompt（不落库不打日志）。
     */
    public SseEmitter askStream(String userId, String scopeId, AskRequest req) {
        PendingAsk pending = beginAsk(userId, scopeId, req);
        ChatClient chatClient = newChatClient();

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        StringBuilder fullText = new StringBuilder();
        AtomicReference<Usage> usageRef = new AtomicReference<>();
        AtomicReference<Disposable> subRef = new AtomicReference<>();
        AtomicBoolean archivedRef = new AtomicBoolean(); // 归档成功后置位，防止断开回调把 ok 改写为 failed
        // 动作外壳流式截断（规范⑤）：delta 通道永不漏外壳片段（含跨 chunk 拆分的半截标签），
        // 开标签前的正文照常透传、闭合后恢复壳后正文——口径与 done 阶段 parse 的 cleanedText 逐条对齐
        ActionShellStreamFilter shellFilter = new ActionShellStreamFilter();

        Disposable disposable = chatClient.prompt(pending.prompt())
            .stream()
            .chatResponse()
            .subscribe(
            chunk -> {
                if (
                    chunk.getMetadata() != null &&
                    chunk.getMetadata().getUsage() != null
                ) {
                    usageRef.set(chunk.getMetadata().getUsage()); // 末分片携带 usage
                }
                String delta = textOf(chunk);
                if (delta.isEmpty()) {
                    return;
                }
                fullText.append(delta);
                String emit = shellFilter.filter(delta);
                if (emit.isEmpty()) {
                    return;
                }
                safeSend(
                    emitter,
                    subRef,
                    SseEmitter.event()
                        .name("delta")
                        .data(new DeltaEvent(emit), MediaType.APPLICATION_JSON)
                );
            },
            error -> {
                log.warn(
                    "SSE 流中 LLM 异常: messageId={}",
                    pending.userMsg().getId(),
                    error
                );
                markUserMessageFailed(pending.userMsg().getId());
                safeSend(
                    emitter,
                    subRef,
                    SseEmitter.event()
                        .name("error")
                        .data(
                            new ErrorEvent(
                                503,
                                "UPSTREAM_ERROR",
                                rootMsg(error)
                            ),
                            MediaType.APPLICATION_JSON
                        )
                );
                emitter.complete();
            },
            () -> {
                try {
                    // 流尾兜底：扣留的疑似标签前缀按正文补发（无完整标签=原文口径），使流式视图收敛到权威全文
                    String tail = shellFilter.flush();
                    if (!tail.isEmpty()) {
                        safeSend(
                            emitter,
                            subRef,
                            SseEmitter.event()
                                .name("delta")
                                .data(new DeltaEvent(tail), MediaType.APPLICATION_JSON)
                        );
                    }
                    // 阶段二：动作块提取 + 归档 assistant + userMsg.status→ok（新事务），content 以剔除动作块后的全文为权威
                    CopilotStatActionExtractor.Parsed output =
                        CopilotStatActionExtractor.parse(fullText.toString());
                    logActionBlockAnomaly(fullText.toString(), output);
                    String authoritative =
                        output != null
                            ? output.cleanedText()
                            : fullText.toString();
                    AskResponse resp = persistAssistant(
                        userId,
                        pending.userMsg(),
                        authoritative,
                        usageRef.get(),
                        output == null ? null : output.actions()
                    );
                    archivedRef.set(true);
                    safeSend(
                        emitter,
                        subRef,
                        SseEmitter.event()
                            .name("done")
                            .data(resp, MediaType.APPLICATION_JSON)
                    );
                    emitter.complete(); // 发完 done 必须关闭流
                } catch (Exception e) {
                    log.error(
                        "SSE 阶段二归档失败: messageId={}",
                        pending.userMsg().getId(),
                        e
                    );
                    markUserMessageFailed(pending.userMsg().getId());
                    safeSend(
                        emitter,
                        subRef,
                        SseEmitter.event()
                            .name("error")
                            .data(
                                new ErrorEvent(
                                    503,
                                    "UPSTREAM_ERROR",
                                    "结果归档失败: " + rootMsg(e)
                                ),
                                MediaType.APPLICATION_JSON
                            )
                    );
                    emitter.complete();
                }
            }
        );
        subRef.set(disposable);

        // 客户端断开/容器超时 → 取消上游订阅（openai-java 流随之关闭），并把 userMsg 标记 failed：
        // 断开即本次失败，同 cid 立即重发可走续跑，不必等 pending 窗口 60s。
        // 注意 onCompletion 在正常完结时也会触发（此时已归档 ok），只有断开/超时路径才允许改状态。
        Runnable cancel = () -> {
            Disposable d = subRef.get();
            if (d != null && !d.isDisposed()) {
                d.dispose();
            }
        };
        Runnable cancelAndFail = () -> {
            cancel.run();
            if (!archivedRef.get()) {
                markUserMessageFailed(pending.userMsg().getId());
            }
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancelAndFail);
        emitter.onError(t -> cancelAndFail.run());
        return emitter;
    }

    /**
     * 阶段一（同步、可快速失败）：参数校验 → 限流 → 幂等门控（回放/续跑/放行）→
     * userMsg 落库提交（REQUIRES_NEW）→ 懒清理 → Prompt 组装。
     * 失败抛 BusinessException：JSON 路径由控制器 catch 回信封；SSE 路径由控制器
     * 手工以 application/json 写出同一信封（Accept 仅 text/event-stream 时
     * 异常 advice 的 JSON 会因协商 406，不能靠 @ExceptionHandler 回落）。
     */
    private PendingAsk beginAsk(String userId, String scopeId, AskRequest req) {
        if (!llmRegistry.isReady(com.zzh.llm.LlmTiers.MAX)) {
            throw new BusinessException(503, "AI 服务未配置");
        }
        if (!StringUtils.hasText(req.getQuestion())) {
            throw new BusinessException(400, "问题内容不能为空");
        }
        if (
            StringUtils.hasText(req.getFocusBlockId()) &&
            req.getFocusBlockId().trim().length() > 100
        ) {
            // focusBlockId 拼入 Redis key，限长与 scopeId 列宽(100)一致，防脏数据滥用 key 空间
            throw new BusinessException(
                400,
                "focusBlockId 过长（上限 100 字符）"
            );
        }
        if (
            StringUtils.hasText(req.getTaskType()) &&
            req.getTaskType().trim().length() > 64
        ) {
            // taskType 仅参与模版路由（不拼 key），未知值宽松回落不报错；仅限长防滥用
            throw new BusinessException(400, "taskType 过长（上限 64 字符）");
        }
        // 1. 限流检查
        rateLimiter.check(userId);
        // 2. 幂等两段式 + pending 互斥门控
        return executeGate(userId, scopeId, req);
    }

    /** 阶段一产物：可直接调 LLM 的 Prompt + 已落库（status=pending）的 userMsg */
    private record PendingAsk(Prompt prompt, AiChatMessage userMsg) {}

    // ==================== Idempotency Gate / 阶段一 ====================

    /**
     * 幂等门控（v1.5.1 新增 pending 互斥）：
     * 查 cid → 找到已有 user 行 → 判断状态 → 决定续跑 / 拦截 / 放行。
     * pending 超窗（如崩溃重启导致残留）或 status=failed（LLM/归档失败，SSE 重发场景）→ 续跑既有行；
     * 在窗 pending（同一 cid 并发双击）与已归档 ok 行 → 拦截拒绝。
     */
    private PendingAsk executeGate(
        String userId,
        String scopeId,
        AskRequest req
    ) {
        Optional<AiChatMessage> userMsgOpt =
            messageRepository.findActiveByClientMessageId(
                req.getClientMessageId()
            );
        if (userMsgOpt.isPresent()) {
            AiChatMessage existingUserMsg = userMsgOpt.get();
            if (
                isPendingExpired(existingUserMsg) ||
                "failed".equals(existingUserMsg.getStatus())
            ) {
                log.debug(
                    "续跑请求: status={}, sessionId={}, messageId={}",
                    existingUserMsg.getStatus(),
                    existingUserMsg.getSessionId(),
                    existingUserMsg.getId()
                );
                return prepareResume(userId, scopeId, req, existingUserMsg);
            }
            // pending 在窗内（同一 cid 并发双击）→ 拦截拒绝
            log.info(
                "同 cid 并发拦截（在窗 pending）: clientId={}",
                req.getClientMessageId()
            );
            throw new BusinessException(
                409,
                "上一次提问仍在处理中，请稍后再试"
            );
        }
        return prepareNew(userId, scopeId, req);
    }

    /** 阶段一·新流程：获取/创建 session → save userMsg（REQUIRES_NEW）→ 懒清理 → 组装 prompt */
    private PendingAsk prepareNew(
        String userId,
        String scopeId,
        AskRequest req
    ) {
        // Session 获取/创建（REQUIRES_NEW 独立事务，防撞唯一索引污染主事务）
        AiChatSession session = resolveSession(
            userId,
            scopeId,
            req.getSessionTitle()
        );
        AiChatMessage userMsg = saveUserMessageInTxn(session, req);
        // 懒清理 & 组装 prompt
        softDeleteOverflowIfNeeded(userMsg.getSessionId());
        List<AiChatMessage> recentHistory = getRecentHistory(
            userMsg.getSessionId()
        );
        Prompt prompt = buildPrompt(
            userId,
            userMsg.getSessionId(),
            userMsg.getContent(),
            recentHistory,
            req,
            scopeId
        );
        return new PendingAsk(prompt, userMsg);
    }

    /** 阶段一·续跑：确认 session → 重挂互斥 status→pending → 复用原 userMsg 行组装 prompt（不新写） */
    private PendingAsk prepareResume(
        String userId,
        String scopeId,
        AskRequest req,
        AiChatMessage existingUserMsg
    ) {
        // 确认 session 存在且未删除
        var sessionOpt = sessionStore.findByUserIdAndScopeId(userId, scopeId);
        if (sessionOpt.isEmpty()) {
            log.warn(
                "续跑时发现 session 不存在: userId={}, scopeId={}，回到新流程",
                userId,
                scopeId
            );
            return prepareNew(userId, scopeId, req);
        }
        // 重挂互斥：status→pending（事务由仓储方法上的 @Transactional 提供）
        messageRepository.updateStatus(existingUserMsg.getId(), "pending");
        List<AiChatMessage> recentHistory = getRecentHistory(
            existingUserMsg.getSessionId()
        );
        Prompt prompt = buildPrompt(
            userId,
            existingUserMsg.getSessionId(),
            existingUserMsg.getContent(),
            recentHistory,
            req,
            scopeId
        );
        return new PendingAsk(prompt, existingUserMsg);
    }

    // ==================== Session Management ====================

    /**
     * 解析或创建 session（通过 REQUIRES_NEW bean 隔离，防 DataIntegrityViolation 污染主事务）。
     */
    private AiChatSession resolveSession(
        String userId,
        String scopeId,
        String sessionTitle
    ) {
        String title =
            sessionTitle == null || sessionTitle.isBlank()
                ? ""
                : sessionTitle.trim();
        Optional<AiChatSession> result = sessionStore.getOrCreate(
            userId,
            scopeId,
            title
        );
        while (result.isEmpty()) {
            // 撞索引 → 其他线程已创建 → 重查复用
            log.info(
                "Session 撞唯一索引（REQUIRES_NEW 回退重查），等待后重试: userId={}, scopeId={}",
                userId,
                scopeId
            );
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            result = sessionStore.getOrCreate(userId, scopeId, title);
        }
        // 同步 title（页面标题可能切换）
        AiChatSession session = result.get();
        if (session.getTitle().isEmpty()) {
            session.setTitle(title.isEmpty() ? "默认会话" : title);
            sessionRepository.save(session);
        }
        return session;
    }

    // ==================== Message Persistence ====================

    /**
     * REQUIRES_NEW 事务模板：仅用于「多写原子组」——userMsg+session 同事务落库、
     * assistant 行+状态翻转同事务归档。私有方法自调用不经过 Spring 代理，
     * @Transactional 会失效，故用编程式事务。
     * 单条 @Modifying 写无需此模板：仓储方法已标注 @Transactional。
     */
    private TransactionTemplate requiresNewTxn() {
        TransactionTemplate tx = new TransactionTemplate(txnMgr);
        tx.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
        return tx;
    }

    /** 在独立事务中保存 User Message（status=pending），返回已提交的行（含生成主键） */
    private AiChatMessage saveUserMessageInTxn(
        AiChatSession session,
        AskRequest req
    ) {
        String overview = req.getContextOverview();
        if (overview != null && overview.length() > 255) {
            throw new BusinessException(413, "摘要内容超过上限 255 字符");
        }
        AiChatMessage msg = AiChatMessage.builder()
            .sessionId(session.getId())
            .role("user")
            .content(req.getQuestion())
            .clientMessageId(req.getClientMessageId())
            .status("pending") // v1.5.1：初始 pending，非 ok
            .contextOverview(overview)
            .timeAnchor(req.getTimeAnchor())
            .channel("deepseek")
            .model(llmRegistry.model(com.zzh.llm.LlmTiers.MAX))
            .ctime(nowSec())
            .deletedAt(0L)
            .build();
        return requiresNewTxn().execute(status -> {
            messageRepository.save(msg);
            session.setLastMessageAt(msg.getCtime());
            sessionRepository.save(session);
            return msg;
        });
    }

    /**
     * 异步回写 userMsg.status→failed（LLM 失败/归档失败/客户端断开时）。
     * 调用方均为无外层事务的回调/catch 块，事务由仓储方法上的 @Transactional 提供。
     */
    private void markUserMessageFailed(Long messageId) {
        try {
            messageRepository.updateStatus(messageId, "failed");
            log.debug("标记 userMsg failed: messageId={}", messageId);
        } catch (Exception e) {
            log.error("标记 userMsg failed 异常: messageId={}", messageId, e);
        }
    }

    /** 懒清理：超出容量时软删最旧记录 */
    private void softDeleteOverflowIfNeeded(Long sessionId) {
        int maxMessages = 200;
        long active = messageRepository.countActiveBySessionId(sessionId);
        if (active > maxMessages) {
            int overflow = (int) (active - maxMessages);
            // 事务由仓储方法上的 @Transactional 提供
            messageRepository.softDeleteOverflow(sessionId, nowSec(), overflow);
            log.info(
                "懒清理覆盖: sessionId={}, 溢出数={}",
                sessionId,
                overflow
            );
        }
    }

    // ==================== LLM Integration ====================

    /**
     * 获取最近 N 条活跃消息（仓储按 id 倒序取窗口，此处反转为正序再喂 Prompt）。
     * 仓储 LIMIT 6 ORDER BY id DESC（最新在前）；LLM 需要真实时间线（旧→新），
     * 倒序会让模型把最新轮次当最旧上下文，多轮因果全错 —— 修复：调用方从未执行约定的反转。
     */
    private List<AiChatMessage> getRecentHistory(Long sessionId) {
        List<AiChatMessage> recent = messageRepository
            .findRecentActiveBySessionId(sessionId)
            .stream()
            .filter(m -> "ok".equals(m.getStatus()))
            .collect(java.util.stream.Collectors.toList());
        java.util.Collections.reverse(recent);
        return recent;
    }

    /** 历史轮次时间前缀格式（服务器本地时区，仅到分钟） */
    private static final DateTimeFormatter HISTORY_TIME_FMT =
        DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** epoch 秒 → "MM-dd HH:mm"（null 安全，作历史轮次前缀） */
    private String formatCtime(Long ctime) {
        if (ctime == null) return "";
        return HISTORY_TIME_FMT.format(
            Instant.ofEpochSecond(ctime).atZone(ZoneId.systemDefault())
        );
    }

    /**
     * 构建 Prompt：系统指令（当次 ephemeral 页面快照 + 数据新鲜度规则）
     *            + 历史 ok 消息（带采集时间前缀，正序） + 当前提问。
     * 快照只进 SystemMessage：历史回放永远纯文本问答，杜绝旧快照与新数据混淆（D7 漂移对策）。
     * 时间隔离（P0）：历史轮次带时间前缀 + 新鲜度裁决规则 —— 历史数字仅为当时状态，
     * 与本次实时快照冲突时以本次为准，防数据变动后旧回答污染新结论。
     * 区块级模版路由（P1）：只替换开头人设段，快照/新鲜度规则等全局段恒保留。
     */
    private Prompt buildPrompt(
        String userId,
        Long sessionId,
        String currentQuestion,
        List<AiChatMessage> history,
        AskRequest req,
        String scopeId
    ) {
        // 任务型模版路由（custom_stat）：命中即整段替换系统提示词，人设/快照/新鲜度段不叠加；
        // taskType 缺省/未知、模版未配置或读取异常 → null，回落既有聊天链路（行为零变化，宽松降级）
        String taskSystem =
            CopilotTaskPromptRenderer.buildCustomStatSystemPrompt(
                req.getTaskType(),
                req.getContextSummary(),
                currentQuestion,
                tag -> promptResolver.resolveTaskTemplate(tag)
            );
        StringBuilder systemPrompt;
        if (taskSystem != null) {
            systemPrompt = new StringBuilder(taskSystem);
        } else {
            systemPrompt = new StringBuilder(
                promptResolver.resolve(scopeId, req.getFocusBlockId())
            );
            // promptHints 固定区段（free-canvas §2.8）：紧贴基础提示之后、页面快照之前原样拼接——
            // 基础提示与 promptHints 公共段跨轮稳定，紧邻拼放可最大化 LLM 供应商 prompt 缓存前缀；
            // 任务型模版分支不叠加（与记忆/语气卡同口径：统计任务提示词自成体系）
            String promptHints = sanitizePromptHints(req.getPromptHints());
            if (promptHints != null) {
                systemPrompt
                    .append("\n\n【客户端能力提示（富客户端 scope 下发）】\n")
                    .append(promptHints);
            }
            String contextSummary = req.getContextSummary();
            String contextOverview = req.getContextOverview();
            if (contextSummary != null && !contextSummary.isBlank()) {
                systemPrompt
                    .append("\n\n【用户当前页面数据快照（提问时刻采集）】\n")
                    .append(
                        "以下为白名单业务数据 JSON，数值单位以 _units 字典为准，严禁臆造或换算数据中不存在的指标：\n"
                    )
                    .append(contextSummary);
            } else if (contextOverview != null && !contextOverview.isBlank()) {
                systemPrompt
                    .append("\n\n【用户当前页面核心指标（JSON）】\n")
                    .append(contextOverview);
            }
            systemPrompt
                .append("\n\n【数据新鲜度规则】\n")
                .append(
                    "历史对话中的所有数字与结论仅为当时快照状态，不代表当前；"
                )
                .append(
                    "若与本次提供的实时页面快照冲突，一律以本次实时快照为准，"
                )
                .append("并主动向用户指出数据相比历史对话已发生变化。");
            // 记忆召回注入（§七）：固定预算分段拼装，冷启动全空时整体省略；
            // 任务型模版分支（taskSystem != null）不叠加——统计任务提示词自成体系
            String memoryInjection = memoryRecall.buildInjection(
                userId,
                sessionId
            );
            if (memoryInjection != null) {
                systemPrompt.append(memoryInjection);
            }
            // 博主语气卡注入（mcp-blogger-kb 拼装口径第三段）：事实引书 + 观点标博主靠
            // kb_search 工具调用时 LLM 自主完成，语气约束在此静态注入；无卡/降级返回 null 零感知
            String personaSegment = personaInjection.buildInjection(req.getBlogger());
            if (personaSegment != null) {
                systemPrompt.append(personaSegment);
            }
            // 选股引导约定（D12/G2）：固定短段全聊天面注入，任务型模版分支不叠加（与记忆/语气卡同口径）
            systemPrompt.append(GUIDE_FLOW_CONVENTION);
            // 全局动作输出规范（外壳协议归后端宣讲，free-canvas §2.8 修订）：标签与解析器常量同源，
            // 置于系统提示最末；任务型模版分支自带外壳教学不叠加（与记忆/语气卡同口径）
            systemPrompt.append(CopilotStatActionExtractor.ACTION_OUTPUT_CONTRACT);
        }
        List<org.springframework.ai.chat.messages.Message> messages =
            new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt.toString()));
        // 会话身份注入（docs/notify/design.md §七）：reminder_* 工具的 userId 由 copilot
        // 持会话身份传入——LLM 无从得知用户身份，必须在系统提示词中显式携带并要求原样透传
        messages.add(new SystemMessage(
            "【会话身份】当前用户 userId=" + userId
                + "。调用 reminder_create/reminder_list/reminder_update/reminder_cancel 工具时，"
                + "userId 参数必须原样传入此值，不得虚构或省略。"
        ));
        for (AiChatMessage m : history) {
            String prefix = "[" + formatCtime(m.getCtime()) + "] ";
            if ("user".equals(m.getRole())) messages.add(
                new UserMessage(prefix + m.getContent())
            );
            else if ("assistant".equals(m.getRole())) messages.add(
                new AssistantMessage(prefix + m.getContent())
            );
        }
        messages.add(new UserMessage(currentQuestion));
        org.springframework.ai.chat.prompt.ChatOptions options = mcpOptions();
        return options == null ? new Prompt(messages) : new Prompt(messages, options);
    }

    /**
     * 构造带内建工具执行环的 ChatClient（spring-ai 2.0.1 官方形态）。
     * <p>防腐推演：2.0.1 起 OpenAiChatModel 不再内部执行工具（仅 resolveToolDefinitions），
     * 裸模型 stream() 下模型的 tool_call 原样返回、无人执行——聚合文本为空串且状态 ok 的
     * 静默丢失（联调实测：模型三次 fetch_kline 调用全部蒸发，「三坑」之后的第四坑）。
     * ChatClient 默认装配 ToolCallingAdvisor 补回执行环：工具轮次 delta 对订阅者无缝续传，
     * 跨轮 usage 由 advisor 累计兜底（末 chunk 即全量），工具调用 chunk 在下游被过滤；
     * 工具轮次上限由默认 ToolCallingManager 兜底（40 次/工具、150 次总量）。
     * 每次请求新建轻量包装：OpenAiChatModel 本身经 LlmRegistry 缓存，构造失败语义与
     * 既有「ask 时 fail-fast」一致（不在容器装配期炸启动）。</p>
     */
    private ChatClient newChatClient() {
        return ChatClient.builder(llmRegistry.chatModel(com.zzh.llm.LlmTiers.MAX)).build();
    }

    /**
     * MCP 工具挂载（步 5 定案）：只挂 :18083 orchestration dispatch 单连接，工具经网关分流。
     * 运行时 options 经 LlmRegistry 工厂构造（model/temperature 显式携带，reason tier 与
     * copilot 渠道同源 OPENAI_MAX_* 三键）。MCP client 未启用/未装配时返回 null
     * （Prompt 无 options，走 tier 模型自身 defaultOptions，纯聊天零工具）。
     */
    private org.springframework.ai.chat.prompt.ChatOptions mcpOptions() {
        org.springframework.ai.tool.ToolCallbackProvider provider =
            mcpToolCallbacksProvider.getIfAvailable();
        if (provider == null) {
            return null;
        }
        // 必须经 LlmRegistry.runtimeOptions 构造（stock-calculator-llm）：
        // spring-ai 2.0.1 三坑——① 运行时 options 必须是 OpenAiChatOptions（createRequest 硬 cast）；
        // ② model 必须显式携带（缺省时 openai-java 以内置 gpt-5-mini 发出 → 渠道 404）；
        // ③ 采样参数只读运行时 options。工厂统一封死，此处不再手写 builder。
        return llmRegistry.runtimeOptions(
            com.zzh.llm.LlmTiers.MAX,
            provider.getToolCallbacks()
        );
    }

    /**
     * 调用 LLM 模型（在事务外执行，不占用数据库连接）。
     * 底层走 SSE 流式再聚合为单响应：中转渠道网关有 60s 硬上限，非流式长生成必被 504 掐断；
     * 流式持续有字节流动可绕过，且渠道默认 streamOptions.includeUsage，token 统计不丢。
     */
    private ChatResponse callLlm(Prompt prompt) {
        ChatClient chatClient = newChatClient();
        long start = System.nanoTime();
        try {
            AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
            new MessageAggregator()
                .aggregate(chatClient.prompt(prompt).stream().chatResponse(), aggregated::set)
                .then()
                .block();
            log.info(
                "DeepSeek LLM 调用完成: cost={}ms",
                (System.nanoTime() - start) / 1_000_000
            );
            ChatResponse response = aggregated.get();
            if (response == null) {
                throw new BusinessException(503, "DeepSeek 空响应");
            }
            return response;
        } catch (BusinessException e) {
            throw e; // 业务异常原样透传
        } catch (com.openai.errors.RateLimitException e) {
            throw new BusinessException(503, "DeepSeek 限流: " + rootMsg(e));
        } catch (com.openai.errors.InternalServerException e) {
            throw new BusinessException(
                503,
                "DeepSeek 服务端异常: " + rootMsg(e)
            );
        } catch (com.openai.errors.UnauthorizedException e) {
            throw new BusinessException(
                503,
                "DeepSeek 鉴权失败: " + rootMsg(e)
            );
        } catch (com.openai.errors.OpenAIIoException e) {
            // callTimeout 到期会先 cancel HTTP/2 流（StreamReset: CANCEL）再以 IO 异常抛出，与服务端 5xx 区分
            throw new BusinessException(
                503,
                "DeepSeek 网络异常或响应超时: " + rootMsg(e)
            );
        } catch (Exception e) {
            log.error("DeepSeek LLM 调用异常", e);
            throw new BusinessException(503, "LLM 调用失败: " + e.getMessage());
        }
    }

    /**
     * 阶段二归档（新事务）：写 assistant 行 + userMsg.status→ok。
     * content 由调用方提供权威全文（阻塞路径=聚合响应；流式路径=delta 累计，均已剔除动作块），
     * usage 可空则 token 计 0；actions 仅随响应下发（ephemeral：不落库、不打日志）。
     */
    private AskResponse persistAssistant(
        String userId,
        AiChatMessage userMsg,
        String content,
        Usage usage,
        List<CopilotActionItem> actions
    ) {
        // 动作类型打点（free-canvas §2.8 观测建议）：只记 type 与条数、不记 payload 内容——
        // payload 语义守卫在前端白名单（静默丢弃后端不可见），此处提供「LLM 吐没吐动作」的服务端唯一观测位；
        // 无动作轮次不打点（常态零噪音）
        if (actions != null && !actions.isEmpty()) {
            List<String> types = actions.stream()
                .map(CopilotActionItem::getType)
                .toList();
            log.info(
                "copilot 动作块下发: sessionId={}, count={}, types={}",
                userMsg.getSessionId(),
                types.size(),
                types
            );
        }
        int promptTokens = 0;
        int completionTokens = 0;
        if (usage != null) {
            if (usage.getPromptTokens() != null) promptTokens =
                usage.getPromptTokens();
            if (usage.getCompletionTokens() != null) completionTokens =
                usage.getCompletionTokens();
        }
        AiChatMessage assistant = AiChatMessage.builder()
            .sessionId(userMsg.getSessionId())
            .role("assistant")
            .content(content == null ? "" : content.trim())
            .status("ok")
            .channel("deepseek")
            .model(llmRegistry.model(com.zzh.llm.LlmTiers.MAX))
            .promptTokens(promptTokens)
            .completionTokens(completionTokens)
            .ctime(nowSec())
            .deletedAt(0L)
            .build();
        requiresNewTxn().execute(status -> {
            messageRepository.save(assistant);
            messageRepository.updateStatus(userMsg.getId(), "ok");
            return null;
        });
        // 落库已提交 → 发布记忆提炼轻量种子（事务模板返回即 AFTER_COMMIT 语义，§五）；
        // 固化链路失败自吞，绝不影响对话主链路（§九失败隔离）
        memoryService.publishSeed(userId, userMsg.getSessionId());
        return buildAskResponse(userMsg, assistant, actions);
    }

    // ==================== Utilities ====================

    /** 检查 userMsg 是否已过期（pending 超过 PENDING_WINDOW_SECONDS 视为陈旧残留） */
    private boolean isPendingExpired(AiChatMessage msg) {
        if (!"pending".equals(msg.getStatus())) return false; // non-pending → 可续跑
        long elapsed = nowSec() - msg.getCtime();
        return elapsed >= PENDING_WINDOW_SECONDS;
    }

    /**
     * 动作块异常观测（打点的另一半）：块被剔除但解析不出有效动作 = fail-open 静默丢弃，
     * 且 content 同步被剔空——前端只会看到 actions=null + content="" 完全无感（联调实测踩坑：
     * V4-Flash 吐了块但 JSON 不合法/未闭合）。此处补 warn 定位失败模式——只报形状指标
     * （全文长度/有无闭合标签），不报内容，维持 actions/payload 不落库不打日志纪律。
     */
    private void logActionBlockAnomaly(
        String rawText,
        CopilotStatActionExtractor.Parsed output
    ) {
        if (output != null && output.actions() == null) {
            log.warn(
                "copilot 动作块解析失败(fail-open): rawLen={}, hasCloseTag={}",
                rawText == null ? 0 : rawText.length(),
                rawText != null
                    && rawText.contains(CopilotStatActionExtractor.CLOSE_TAG)
            );
        }
    }

    private AskResponse buildAskResponse(
        AiChatMessage user,
        AiChatMessage assistant,
        List<CopilotActionItem> actions
    ) {
        return AskResponse.builder()
            .userMessageId(user.getId())
            .assistantMessageId(assistant != null ? assistant.getId() : null)
            .content(assistant != null ? assistant.getContent() : "")
            .promptTokens(assistant != null ? assistant.getPromptTokens() : 0)
            .completionTokens(
                assistant != null ? assistant.getCompletionTokens() : 0
            )
            .channel(assistant != null ? assistant.getChannel() : null)
            .userContextOverview(user.getContextOverview())
            .userTimeAnchor(user.getTimeAnchor())
            .ctime(assistant != null ? assistant.getCtime() : nowSec())
            .actions(actions)
            .build();
    }

    private static String rootMsg(Throwable e) {
        Throwable cause = e.getCause();
        return cause != null ? cause.getMessage() : e.getMessage();
    }

    /** 提取单 chunk 增量文本（usage-only 末分片/空 result 安全返回空串） */
    private static String textOf(ChatResponse chunk) {
        if (
            chunk.getResult() == null ||
            chunk.getResult().getOutput() == null ||
            chunk.getResult().getOutput().getText() == null
        ) {
            return "";
        }
        return chunk.getResult().getOutput().getText();
    }

    /** 提取响应 usage（流式路径取末分片，渠道默认 streamOptions.includeUsage） */
    private static Usage usageOf(ChatResponse response) {
        return response != null && response.getMetadata() != null
            ? response.getMetadata().getUsage()
            : null;
    }

    /**
     * promptHints 清洗（纯函数，包私有供单测直达）：blank → null（整段省略，非富客户端轮次零变化）；
     * 超 {@link #PROMPT_HINTS_MAX_BYTES} → 按字符边界截断。不可信输入契约（free-canvas §2.8）：
     * 只截断不校验内容、不落库不打日志；「原样」指内容零改写透传（片段文本全由前端维护）。
     * <p>防腐推演：字节截断点可能落在多字节 UTF-8 序列中间——续字节恒为 10xxxxxx，从截断点
     * 回退跳过续字节后若仍是负数字节（lead byte）则该字符不完整，一并舍弃，保证产物解码
     * 不出现 U+FFFD 乱码且必为原串前缀。</p>
     */
    static String sanitizePromptHints(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= PROMPT_HINTS_MAX_BYTES) {
            return raw;
        }
        int len = PROMPT_HINTS_MAX_BYTES;
        while (len > 0 && (bytes[len - 1] & 0xC0) == 0x80) {
            len--;
        }
        if (len > 0 && bytes[len - 1] < 0) {
            len--; // lead byte：其续字节已被截掉，字符不完整
        }
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }

    /** SSE 发送兑底：客户端已断开时取消上游订阅（openai-java 流随之关闭），静默收尾 */
    private void safeSend(
        SseEmitter emitter,
        AtomicReference<Disposable> subRef,
        SseEmitter.SseEventBuilder event
    ) {
        try {
            emitter.send(event);
        } catch (Exception e) {
            log.info("SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
            Disposable d = subRef.get();
            if (d != null && !d.isDisposed()) {
                d.dispose();
            }
        }
    }

    /** 级联软删除 */
    @Transactional
    public void cascadeDeleteByScopeId(String userId, String scopeId) {
        log.info("级联清理: userId={}, scopeId={}", userId, scopeId);
        var sessionOpt = sessionRepository.findActiveByUserIdAndScopeId(
            userId,
            scopeId
        );
        if (sessionOpt.isPresent()) {
            AiChatSession session = sessionOpt.get();
            long now = nowSec();
            session.setDeletedAt(now);
            sessionRepository.save(session);
            messageRepository.cascadeDeleteBySessionId(session.getId(), now);
            log.info("Copilot 级联清理完成: sessionId={}", session.getId());
        }
    }

    private static long nowSec() {
        return System.currentTimeMillis() / 1000;
    }
}
