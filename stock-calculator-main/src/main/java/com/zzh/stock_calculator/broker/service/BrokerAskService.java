package com.zzh.stock_calculator.broker.service;

import com.zzh.stock_calculator.broker.dto.BrokerDtos;
import com.zzh.stock_calculator.broker.util.BrokerRateLimiter;
import com.zzh.stock_calculator.broker.util.FullCodeNormalizer;
import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.copilot.CopilotAskApi;
import com.zzh.stock_calculator.crawler.StockDirectoryApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 画布经纪问询服务（free-canvas v3 §3.2）：复用 copilot askStream SSE 管道
 * （message/done/error 事件协议、cid 幂等门控、actions 随 done、断连补偿），零新协议。
 * <p>上下文挂载：fullCode/canvasContext/klines 摘要进 contextSummary——只进 Prompt
 * 不落库不打日志（硬约束 #2：明文业务数据不落任何表）。klines 只摘近 10 根入提示词，
 * 完整历史 agent 按路径 B 读自家 stock_mcp 库（§3.2：不要求前端喂全量）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrokerAskService {

    /** scopeId 定案（§3.2）：画布聊天窗独立线程，与 copilot 其他 scope 隔离 */
    public static final String SCOPE_CANVAS = "canvas";

    /** canvasContext 防御截断（契约：超限由前端截断，此为后端兜底） */
    private static final int MAX_CANVAS_CONTEXT_CHARS = 4000;

    /** klines 摘要根数（近端上下文，超出部分 agent 走路径 B 读库） */
    private static final int RECENT_KLINES_IN_PROMPT = 10;

    private final BrokerRateLimiter rateLimiter;
    private final StockDirectoryApi stockDirectoryApi;
    private final CopilotAskApi copilotAskApi;
    private final com.zzh.stock_calculator.broker.config.BrokerProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 供 Controller 做 §三统一约定的 256KB 请求体硬上限判定（Content-Length 缺失时不拦截） */
    public void checkPayloadLimit(long contentLength) {
        if (contentLength > 0 && contentLength > properties.getRateLimit().getPayloadLimitBytes()) {
            throw new BusinessException(413, "请求体超过 " + properties.getRateLimit().getPayloadLimitBytes() + " 字节上限");
        }
    }

    /** 阶段一校验 + 组装上下文 + 委托 copilot 管道；失败抛 BusinessException 由 Controller 回落 JSON 信封 */
    public SseEmitter ask(String userId, BrokerDtos.CanvasAskRequest request) {
        rateLimiter.checkAsk(userId);
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw new BusinessException(400, "question 缺失");
        }
        if (request.getCid() == null || request.getCid().isBlank()) {
            throw new BusinessException(400, "cid 缺失（幂等键，前端 newClientMessageId）");
        }
        String fullCode = null;
        if (request.getFullCode() != null && !request.getFullCode().isBlank()) {
            String code = FullCodeNormalizer.toStockCode(request.getFullCode());
            if (!stockDirectoryApi.existsBySixDigit(code)) {
                throw new BusinessException(400, "未收录的股票代码: " + request.getFullCode());
            }
            fullCode = code;
        }

        Map<String, Object> context = buildContextSummary(request, fullCode);
        String contextSummary = context.isEmpty() ? null : objectMapper.writeValueAsString(context);
        return copilotAskApi.askStream(userId, SCOPE_CANVAS,
                request.getQuestion().trim(), contextSummary, request.getCid());
    }

    /** 阻塞变体（JSON 路径）：同一校验与上下文组装，走 copilot ask 阻塞管道 */
    public BrokerDtos.CanvasAskData askBlocking(String userId, BrokerDtos.CanvasAskRequest request) {
        rateLimiter.checkAsk(userId);
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw new BusinessException(400, "question 缺失");
        }
        if (request.getCid() == null || request.getCid().isBlank()) {
            throw new BusinessException(400, "cid 缺失（幂等键，前端 newClientMessageId）");
        }
        String fullCode = null;
        if (request.getFullCode() != null && !request.getFullCode().isBlank()) {
            String code = FullCodeNormalizer.toStockCode(request.getFullCode());
            if (!stockDirectoryApi.existsBySixDigit(code)) {
                throw new BusinessException(400, "未收录的股票代码: " + request.getFullCode());
            }
            fullCode = code;
        }
        Map<String, Object> context = buildContextSummary(request, fullCode);
        String contextSummary = context.isEmpty() ? null : objectMapper.writeValueAsString(context);
        CopilotAskApi.AskResult result = copilotAskApi.askBlocking(userId, SCOPE_CANVAS,
                request.getQuestion().trim(), contextSummary, request.getCid());
        return BrokerDtos.CanvasAskData.builder()
                .content(result.content())
                .actions(result.actions())
                .build();
    }

    private Map<String, Object> buildContextSummary(BrokerDtos.CanvasAskRequest request, String fullCode) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (fullCode != null) {
            context.put("fullCode", fullCode);
        }
        if (request.getCanvasContext() != null && !request.getCanvasContext().isBlank()) {
            String ctx = request.getCanvasContext().trim();
            if (ctx.length() > MAX_CANVAS_CONTEXT_CHARS) {
                ctx = ctx.substring(0, MAX_CANVAS_CONTEXT_CHARS);
            }
            context.put("canvasContext", ctx);
        }
        if (request.getKlines() != null && !request.getKlines().isEmpty()) {
            int n = request.getKlines().size();
            context.put("klinesCount", n);
            // 近端切片入提示词（文本行，紧凑防 prompt 爆炸）；完整历史 agent 读库
            StringBuilder recent = new StringBuilder();
            request.getKlines().stream()
                    .skip(Math.max(0, n - RECENT_KLINES_IN_PROMPT))
                    .forEach(s -> recent.append(String.format("%s O%s C%s H%s L%s V%d%n",
                            s.getDate(), s.getOpen(), s.getClose(), s.getHigh(), s.getLow(),
                            s.getVolume() == null ? 0 : s.getVolume())));
            context.put("recentKlines", recent.toString().trim());
        }
        return context;
    }
}
