package com.zzh.stock_calculator.copilot.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.copilot.dto.CopilotDtos.AskRequest;
import com.zzh.stock_calculator.copilot.dto.CopilotDtos.AskResponse;
import com.zzh.stock_calculator.copilot.entity.AiChatMessage;
import com.zzh.stock_calculator.copilot.entity.AiChatSession;
import com.zzh.stock_calculator.copilot.repository.AiChatMessageRepository;
import com.zzh.stock_calculator.copilot.repository.AiChatSessionRepository;
import com.zzh.stock_calculator.copilot.config.DeepSeekProperties;
import com.zzh.stock_calculator.copilot.service.store.AiChatSessionStore;
import com.zzh.stock_calculator.copilot.util.CopilotPromptResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    private final DeepSeekProperties deepSeekProps;
    private final PlatformTransactionManager txnMgr; // for LLM failure recovery
    private final ObjectProvider<OpenAiChatModel> deepSeekChatModelProvider;

    /**
     * 显式构造器（项目无 lombok.config，@RequiredArgsConstructor 不会复制 @Qualifier）。
     * deepSeekChatModel 为条件 Bean（base-url 未配置时不装配），ObjectProvider 容错；
     * 不加 @Qualifier 会因 geminiChatModel 的 @Primary 静默注入错误渠道。
     * 仅直连 DeepSeek，不经 LlmChainRouter（问答付费渠道与引流免费渠道隔离）。
     */
    public AiChatOrchestrationService(AiChatSessionStore sessionStore,
                                      AiChatMessageRepository messageRepository,
                                      AiChatSessionRepository sessionRepository,
                                      CopilotPromptResolver promptResolver,
                                      com.zzh.stock_calculator.copilot.util.AiChatRateLimiter rateLimiter,
                                      DeepSeekProperties deepSeekProps,
                                      PlatformTransactionManager txnMgr,
                                      @Qualifier("deepSeekChatModel") ObjectProvider<OpenAiChatModel> deepSeekChatModelProvider) {
        this.sessionStore = sessionStore;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.promptResolver = promptResolver;
        this.rateLimiter = rateLimiter;
        this.deepSeekProps = deepSeekProps;
        this.txnMgr = txnMgr;
        this.deepSeekChatModelProvider = deepSeekChatModelProvider;
    }

    /** LLM 超时窗口（秒）：pending 状态下同一 cid 在窗内视为「请求正在处理中」 */
    private static final int PENDING_WINDOW_SECONDS = 60;

    // ==================== Ask（主方法）====================

    /**
     * 处理用户提问：限流检查 → 幂等验证 → 会话管理 → 消息写入 → LLM 调用 → 归档回复。
     *
     * 架构决策（v1.5.1）：userMsg.save 和 LLM 调用分离——
     * userMsg 先提交到 DB（status='pending'），LLM 调用在事务外执行（不占 DB 连接），
     * 成功后新事务写 assistantMsg，失败后独立事务回滚 userMsg.status→failed。
     */
    public AskResponse ask(String userId, String scopeId, AskRequest req) {
        if (!deepSeekProps.isEnabled() || !StringUtils.hasText(deepSeekProps.getBaseUrl())) {
            throw new BusinessException(503, "AI 服务未配置");
        }
        if (!StringUtils.hasText(req.getQuestion())) {
            throw new BusinessException(400, "问题内容不能为空");
        }
        if (StringUtils.hasText(req.getFocusBlockId()) && req.getFocusBlockId().trim().length() > 100) {
            // focusBlockId 拼入 Redis key，限长与 scopeId 列宽(100)一致，防脏数据滥用 key 空间
            throw new BusinessException(400, "focusBlockId 过长（上限 100 字符）");
        }
        // 1. 限流检查
        rateLimiter.check(userId);
        // 2. 幂等两段式 + pending 互斥门控
        return executeWithIdempotentGate(userId, scopeId, req);
    }

    // ==================== Idempotency Gate ====================

    /**
     * 幂等门控（v1.5.1 新增 pending 互斥）：
     * 查 cid → 找到已有 user 行 → 判断状态 → 决定回放 / 续跑 / 放行。
     */
    private AskResponse executeWithIdempotentGate(String userId, String scopeId, AskRequest req) {
        Optional<AiChatMessage> userMsgOpt = messageRepository.findActiveByClientMessageId(req.getClientMessageId());
        if (userMsgOpt.isPresent()) {
            AiChatMessage existingUserMsg = userMsgOpt.get();
            if (isPendingExpired(existingUserMsg)) {
                // pending 超窗（如崩溃重启导致残留）→ 续跑旧 session
                log.debug("续跑已过期 pending 请求: sessionId={}, messageId={}",
                        existingUserMsg.getSessionId(), existingUserMsg.getId());
                return resumeExistingSession(userId, scopeId, req, existingUserMsg);
            } else {
                // pending 在窗内（同一 cid 并发双击）→ 拦截拒绝
                log.info("同 cid 并发拦截（在窗 pending）: clientId={}", req.getClientMessageId());
                throw new BusinessException(409, "上一次提问仍在处理中，请稍后再试");
            }
        }
        return startNewFlow(userId, scopeId, req);
    }

    /**
     * 开始全新流程：获取/创建 session → 保存 userMsg → LLM 调用 → 归档 assistant。
     * 关键：save userMsg 在独立小事务中完成并立即提交，随后 LLM 调用在事务外执行。
     */
    private AskResponse startNewFlow(String userId, String scopeId, AskRequest req) {
        // Session 获取/创建（REQUIRES_NEW 独立事务，防撞唯一索引污染主事务）
        AiChatSession session = resolveSession(userId, scopeId, req.getSessionTitle());

        // Step A: save userMsg + commit（REQUIRES_NEW，不阻塞后续操作）
        AiChatMessage[] userMsgHolder = new AiChatMessage[1];
        saveUserMessageInTxn(session, req, userMsgHolder);
        AiChatMessage userMsg = userMsgHolder[0];

        // Step B: 懒清理 & 组装 prompt
        softDeleteOverflowIfNeeded(userMsg.getSessionId());
        List<AiChatMessage> recentHistory = getRecentHistory(userMsg.getSessionId());
        Prompt prompt = buildPrompt(userMsg.getContent(), recentHistory, req, scopeId);

        // Step C: === LLM 调用在事务外 ===
        ChatResponse response;
        try {
            response = callLlm(prompt);
        } catch (BusinessException e) {
            // LLM 失败：独立事务更新 userMsg.status→failed（供重试识别）
            markUserMessageFailed(userMsg.getId());
            throw e;
        }

        // Step D: 归档 assistant msg（新事务）
        return persistAssistantResponse(userMsg, response);
    }

    /** 复用已有 pending session：更新 status→pending 并重挂互斥 */
    private AskResponse resumeExistingSession(String userId, String scopeId, AskRequest req,
                                               AiChatMessage existingUserMsg) {
        // 确认 session 存在且未删除
        var sessionOpt = sessionStore.findByUserIdAndScopeId(userId, scopeId);
        if (sessionOpt.isEmpty()) {
            log.warn("续跑时发现 session 不存在: userId={}, scopeId={}，回到新流程", userId, scopeId);
            return startNewFlow(userId, scopeId, req);
        }

        // 重挂互斥：status→pending
        messageRepository.updateStatus(existingUserMsg.getId(), "pending");

        // 组装 prompt + LLM 调用（LLM 在事务外）
        List<AiChatMessage> recentHistory = getRecentHistory(existingUserMsg.getSessionId());
        Prompt prompt = buildPrompt(existingUserMsg.getContent(), recentHistory, req, scopeId);

        ChatResponse response;
        try {
            response = callLlm(prompt);
        } catch (BusinessException e) {
            messageRepository.updateStatus(existingUserMsg.getId(), "failed");
            throw e;
        }

        // 归档 assistant + 回写 status→ok
        persistAssistantResponseForExisting(existingUserMsg, response);
        return buildAskResponse(existingUserMsg, null); // assistantId=null placeholder, updated below
    }

    // ==================== Session Management ====================

    /**
     * 解析或创建 session（通过 REQUIRES_NEW bean 隔离，防 DataIntegrityViolation 污染主事务）。
     */
    private AiChatSession resolveSession(String userId, String scopeId, String sessionTitle) {
        String title = (sessionTitle == null || sessionTitle.isBlank()) ? "" : sessionTitle.trim();
        Optional<AiChatSession> result = sessionStore.getOrCreate(userId, scopeId, title);
        while (result.isEmpty()) {
            // 撞索引 → 其他线程已创建 → 重查复用
            log.info("Session 撞唯一索引（REQUIRES_NEW 回退重查），等待后重试: userId={}, scopeId={}", userId, scopeId);
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
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

    /** 在独立事务中保存 User Message（status=pending） */
    private void saveUserMessageInTxn(AiChatSession session, AskRequest req, AiChatMessage[] holder) {
        String overview = req.getContextOverview();
        if (overview != null && overview.length() > 255) {
            throw new BusinessException(413, "摘要内容超过上限 255 字符");
        }
        AiChatMessage msg = AiChatMessage.builder()
                .sessionId(session.getId())
                .role("user").content(req.getQuestion())
                .clientMessageId(req.getClientMessageId())
                .status("pending") // v1.5.1：初始 pending，非 ok
                .contextOverview(overview).timeAnchor(req.getTimeAnchor())
                .channel("deepseek").model(deepSeekProps.getModel())
                .ctime(nowSec()).deletedAt(0L).build();
        TransactionTemplate tx = new TransactionTemplate(txnMgr);
        tx.setPropagationBehavior(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW.value());
        tx.execute(status -> {
            messageRepository.save(msg);
            session.setLastMessageAt(msg.getCtime());
            sessionRepository.save(session);
            holder[0] = msg;
            return null;
        });
    }

    /** 异步回写 userMsg.status→failed（当 LLM 调用失败时） */
    private void markUserMessageFailed(Long messageId) {
        try {
            TransactionTemplate tx = new TransactionTemplate(txnMgr);
            tx.setPropagationBehavior(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW.value());
            tx.execute(status -> {
                messageRepository.updateStatus(messageId, "failed");
                return null;
            });
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
            messageRepository.softDeleteOverflow(sessionId, nowSec(), overflow);
            log.info("懒清理覆盖: sessionId={}, 溢出数={}", sessionId, overflow);
        }
    }

    // ==================== LLM Integration ====================

    /**
     * 获取最近 N 条活跃消息（仓储按 id 倒序取窗口，此处反转为正序再喂 Prompt）。
     * 仓储 LIMIT 6 ORDER BY id DESC（最新在前）；LLM 需要真实时间线（旧→新），
     * 倒序会让模型把最新轮次当最旧上下文，多轮因果全错 —— 修复：调用方从未执行约定的反转。
     */
    private List<AiChatMessage> getRecentHistory(Long sessionId) {
        List<AiChatMessage> recent = messageRepository.findRecentActiveBySessionId(sessionId)
                .stream().filter(m -> "ok".equals(m.getStatus())).collect(java.util.stream.Collectors.toList());
        java.util.Collections.reverse(recent);
        return recent;
    }

    /** 历史轮次时间前缀格式（服务器本地时区，仅到分钟） */
    private static final DateTimeFormatter HISTORY_TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** epoch 秒 → "MM-dd HH:mm"（null 安全，作历史轮次前缀） */
    private String formatCtime(Long ctime) {
        if (ctime == null) return "";
        return HISTORY_TIME_FMT.format(Instant.ofEpochSecond(ctime).atZone(ZoneId.systemDefault()));
    }

    /**
     * 构建 Prompt：系统指令（当次 ephemeral 页面快照 + 数据新鲜度规则）
     *            + 历史 ok 消息（带采集时间前缀，正序） + 当前提问。
     * 快照只进 SystemMessage：历史回放永远纯文本问答，杜绝旧快照与新数据混淆（D7 漂移对策）。
     * 时间隔离（P0）：历史轮次带时间前缀 + 新鲜度裁决规则 —— 历史数字仅为当时状态，
     * 与本次实时快照冲突时以本次为准，防数据变动后旧回答污染新结论。
     * 区块级模版路由（P1）：只替换开头人设段，快照/新鲜度规则等全局段恒保留。
     */
    private Prompt buildPrompt(String currentQuestion, List<AiChatMessage> history, AskRequest req, String scopeId) {
        String persona = promptResolver.resolve(scopeId, req.getFocusBlockId());
        StringBuilder systemPrompt = new StringBuilder(persona);
        String contextSummary = req.getContextSummary();
        String contextOverview = req.getContextOverview();
        if (contextSummary != null && !contextSummary.isBlank()) {
            systemPrompt.append("\n\n【用户当前页面数据快照（提问时刻采集）】\n")
                    .append("以下为白名单业务数据 JSON，数值单位以 _units 字典为准，严禁臆造或换算数据中不存在的指标：\n")
                    .append(contextSummary);
        } else if (contextOverview != null && !contextOverview.isBlank()) {
            systemPrompt.append("\n\n【用户当前页面核心指标（JSON）】\n").append(contextOverview);
        }
        systemPrompt.append("\n\n【数据新鲜度规则】\n")
                .append("历史对话中的所有数字与结论仅为当时快照状态，不代表当前；")
                .append("若与本次提供的实时页面快照冲突，一律以本次实时快照为准，")
                .append("并主动向用户指出数据相比历史对话已发生变化。");
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt.toString()));
        for (AiChatMessage m : history) {
            String prefix = "[" + formatCtime(m.getCtime()) + "] ";
            if ("user".equals(m.getRole())) messages.add(new UserMessage(prefix + m.getContent()));
            else if ("assistant".equals(m.getRole())) messages.add(new AssistantMessage(prefix + m.getContent()));
        }
        messages.add(new UserMessage(currentQuestion));
        return new Prompt(messages);
    }

    /** 调用 LLM 模型（在事务外执行，不占用数据库连接） */
    private ChatResponse callLlm(Prompt prompt) {
        OpenAiChatModel chatModel = deepSeekChatModelProvider.getIfAvailable();
        if (chatModel == null) {
            throw new BusinessException(503, "AI 服务未配置");
        }
        try {
            return chatModel.call(prompt);
        } catch (BusinessException e) {
            throw e; // 业务异常原样透传
        } catch (com.openai.errors.RateLimitException e) {
            throw new BusinessException(503, "DeepSeek 限流: " + rootMsg(e));
        } catch (com.openai.errors.InternalServerException e) {
            throw new BusinessException(503, "DeepSeek 服务端异常: " + rootMsg(e));
        } catch (com.openai.errors.UnauthorizedException e) {
            throw new BusinessException(503, "DeepSeek 鉴权失败: " + rootMsg(e));
        } catch (Exception e) {
            log.error("DeepSeek LLM 调用异常", e);
            throw new BusinessException(503, "LLM 调用失败: " + e.getMessage());
        }
    }

    /** 持久化 Assistant Response（新事务） */
    private AskResponse persistAssistantResponse(AiChatMessage userMsg, ChatResponse response) {
        AssistantMessage output = response.getResult().getOutput();
        String content = output == null ? "" : (output.getText() == null ? "" : output.getText().trim());
        // Spring AI Usage via metadata.getUsage()
        int promptTokens = 0;
        int completionTokens = 0;
        if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            Usage usage = response.getMetadata().getUsage();
            if (usage.getPromptTokens() != null) promptTokens = usage.getPromptTokens();
            if (usage.getCompletionTokens() != null) completionTokens = usage.getCompletionTokens();
        }
        AiChatMessage assistant = AiChatMessage.builder()
                .sessionId(userMsg.getSessionId()).role("assistant")
                .content(content).status("ok")
                .channel("deepseek").model(deepSeekProps.getModel())
                .promptTokens(promptTokens).completionTokens(completionTokens)
                .ctime(nowSec()).deletedAt(0L).build();

        TransactionTemplate tx = new TransactionTemplate(txnMgr);
        tx.setPropagationBehavior(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW.value());
        tx.execute(status -> {
            messageRepository.save(assistant);
            messageRepository.updateStatus(userMsg.getId(), "ok");
            return null;
        });
        return buildAskResponse(userMsg, assistant);
    }

    /** 回写已存在的 pending userMsg（续跑场景） */
    private void persistAssistantResponseForExisting(AiChatMessage userMsg, ChatResponse response) {
        AssistantMessage output = response.getResult().getOutput();
        String content = output == null ? "" : (output.getText() == null ? "" : output.getText().trim());
        // Same Usage extraction as persistAssistantResponse (DRY)
        int promptTokens = 0;
        int completionTokens = 0;
        if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            Usage usage = response.getMetadata().getUsage();
            if (usage.getPromptTokens() != null) promptTokens = usage.getPromptTokens();
            if (usage.getCompletionTokens() != null) completionTokens = usage.getCompletionTokens();
        }
        AiChatMessage assistant = AiChatMessage.builder()
                .sessionId(userMsg.getSessionId()).role("assistant")
                .content(content).status("ok")
                .channel("deepseek").model(deepSeekProps.getModel())
                .promptTokens(promptTokens).completionTokens(completionTokens)
                .ctime(nowSec()).deletedAt(0L).build();
        TransactionTemplate tx = new TransactionTemplate(txnMgr);
        tx.setPropagationBehavior(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW.value());
        tx.execute(status -> {
            messageRepository.save(assistant);
            messageRepository.updateStatus(userMsg.getId(), "ok");
            return null;
        });
    }

    // ==================== Utilities ====================

    /** 检查 userMsg 是否已过期（pending 超过 PENDING_WINDOW_SECONDS 视为陈旧残留） */
    private boolean isPendingExpired(AiChatMessage msg) {
        if (!"pending".equals(msg.getStatus())) return false; // non-pending → 可续跑
        long elapsed = nowSec() - msg.getCtime();
        return elapsed >= PENDING_WINDOW_SECONDS;
    }

    private AskResponse buildAskResponse(AiChatMessage user, AiChatMessage assistant) {
        return AskResponse.builder()
                .userMessageId(user.getId())
                .assistantMessageId(assistant != null ? assistant.getId() : null)
                .content(assistant != null ? assistant.getContent() : "")
                .promptTokens(assistant != null ? assistant.getPromptTokens() : 0)
                .completionTokens(assistant != null ? assistant.getCompletionTokens() : 0)
                .channel(assistant != null ? assistant.getChannel() : null)
                .userContextOverview(user.getContextOverview())
                .userTimeAnchor(user.getTimeAnchor())
                .ctime(assistant != null ? assistant.getCtime() : nowSec()).build();
    }

    private static String rootMsg(Throwable e) {
        Throwable cause = e.getCause();
        return cause != null ? cause.getMessage() : e.getMessage();
    }

    /** 级联软删除 */
    @Transactional
    public void cascadeDeleteByScopeId(String userId, String scopeId) {
        log.info("级联清理: userId={}, scopeId={}", userId, scopeId);
        var sessionOpt = sessionRepository.findActiveByUserIdAndScopeId(userId, scopeId);
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
