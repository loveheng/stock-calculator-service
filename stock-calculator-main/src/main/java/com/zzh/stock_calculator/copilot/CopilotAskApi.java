package com.zzh.stock_calculator.copilot;

import com.zzh.stock_calculator.copilot.dto.CopilotDtos;
import com.zzh.stock_calculator.copilot.service.AiChatOrchestrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * copilot 基包 API 出口（Modulith 边界：子包 service/dto 对 broker 不可见，
 * broker 只经本门面复用 askStream 管道——free-canvas §3.2）。
 * <p>签名用中性参数而非 DTO，避免跨域引用 copilot.dto：SSE 事件协议
 * （message 增量 / done 权威响应含 actions / error）、cid 幂等门控、限流、
 * 断连取消与 query_task 补偿全部由既有 askStream 管道承担，零复制。</p>
 */
@Service
@RequiredArgsConstructor
public class CopilotAskApi {

    private final AiChatOrchestrationService orchestrationService;

    /** SSE 流式提问；阶段一同步执行，失败抛 BusinessException 由调用方回落 JSON 信封 */
    public SseEmitter askStream(String userId, String scopeId, String question,
                                String contextSummary, String clientMessageId) {
        return orchestrationService.askStream(userId, scopeId, buildRequest(question, contextSummary, clientMessageId));
    }

    /** 阻塞提问（JSON 变体）：返回中性结果形态（actions 转 map 列表，不泄漏 copilot.dto） */
    public AskResult askBlocking(String userId, String scopeId, String question,
                                 String contextSummary, String clientMessageId) {
        var resp = orchestrationService.ask(userId, scopeId,
                buildRequest(question, contextSummary, clientMessageId));
        List<java.util.Map<String, Object>> actions = resp.getActions() == null ? null
                : resp.getActions().stream()
                        .map(a -> {
                            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                            m.put("type", a.getType());
                            m.put("payload", a.getPayload());
                            return m;
                        })
                        .toList();
        return new AskResult(resp.getContent(), actions);
    }

    /** 阻塞问询结果（中性形态：content 全文 + actions 原始 payload） */
    public record AskResult(String content, List<java.util.Map<String, Object>> actions) {}

    private CopilotDtos.AskRequest buildRequest(String question, String contextSummary, String clientMessageId) {
        CopilotDtos.AskRequest req = CopilotDtos.AskRequest.builder()
                .question(question)
                .clientMessageId(clientMessageId)
                .contextSummary(contextSummary)
                .build();
        return req;
    }
}
