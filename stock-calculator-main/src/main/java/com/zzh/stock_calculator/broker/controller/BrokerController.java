package com.zzh.stock_calculator.broker.controller;

import com.zzh.stock_calculator.broker.config.BrokerProperties;
import com.zzh.stock_calculator.broker.dto.BrokerDtos;
import com.zzh.stock_calculator.broker.service.BrokerAskService;
import com.zzh.stock_calculator.broker.service.BrokerComputeService;
import com.zzh.stock_calculator.broker.service.BrokerKlineService;
import com.zzh.stock_calculator.broker.service.MonitorService;
import com.zzh.stock_calculator.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 画布 broker 端点族（free-canvas v3 §三）：K 线读穿代理 + 指标能力端点 + 无状态指标计算
 * + 经纪问询 + 监控任务。鉴权经 AuthInterceptor（WebConfig 挂 /api/broker/**），
 * userId 由 @RequestAttribute 注入。
 */
@Slf4j
@RestController
@RequestMapping("/api/broker")
@RequiredArgsConstructor
public class BrokerController {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final BrokerKlineService klineService;
    private final BrokerComputeService computeService;
    private final BrokerAskService askService;
    private final MonitorService monitorService;
    private final BrokerProperties properties;

    /** §3.4 K 线读穿代理（画布数据主通道）：query 全参数，无请求体 */
    @GetMapping("/klines")
    public ApiResponse<BrokerDtos.KlinesData> klines(
            @RequestAttribute("authUserId") String userId,
            @RequestParam String fullCode,
            @RequestParam(defaultValue = "qfq") String adjustType,
            @RequestParam(defaultValue = "1d") String interval,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ApiResponse.success(
                klineService.getKlines(userId, fullCode, adjustType, interval, from, to));
    }

    /** §3.6 能力端点：前端 brokerService 初始化拉取并缓存 version（失败用上次缓存/首次隐藏选项） */
    @GetMapping("/indicators")
    public ApiResponse<BrokerDtos.IndicatorsData> indicators() {
        BrokerProperties.Indicators cfg = properties.getIndicators();
        List<BrokerDtos.IndicatorInfo> list = cfg.getItems().stream()
                .map(i -> BrokerDtos.IndicatorInfo.builder()
                        .name(i.getName())
                        .label(i.getLabel())
                        .minBars(i.getMinBars())
                        .applicableBlocks(i.getApplicableBlocks())
                        .build())
                .toList();
        return ApiResponse.success(BrokerDtos.IndicatorsData.builder()
                .version(cfg.getVersion())
                .indicators(list)
                .build());
    }

    /** §3.1 无状态复杂指标计算（画布数值指标唯一来源，§5.1 硬条款）：带体端点，256KB 硬上限 */
    @PostMapping("/indicators/compute")
    public ApiResponse<BrokerDtos.ComputeData> compute(
            @RequestAttribute("authUserId") String userId,
            HttpServletRequest request,
            @RequestBody BrokerDtos.ComputeRequest body) {
        computeService.checkPayloadLimit(request.getContentLengthLong());
        return ApiResponse.success(computeService.compute(userId, body));
    }

    /** §3.2 经纪问询 JSON 阻塞变体（无 Accept: text/event-stream 时命中；content + actions 信封） */
    @PostMapping("/ask")
    public ApiResponse<BrokerDtos.CanvasAskData> askJson(
            @RequestAttribute("authUserId") String userId,
            HttpServletRequest request,
            @RequestBody BrokerDtos.CanvasAskRequest body) {
        askService.checkPayloadLimit(request.getContentLengthLong());
        return ApiResponse.success(askService.askBlocking(userId, body));
    }

    /** §3.2 经纪问询 SSE 流式变体（headers 条件优先级高于无条件 JSON 变体）：
     *  message 增量 / done 权威响应（含 actions）/ error；cid 幂等；阶段一失败回落 JSON 信封 */
    @PostMapping(value = "/ask", headers = "Accept=text/event-stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestAttribute("authUserId") String userId,
                                HttpServletRequest request,
                                @RequestBody BrokerDtos.CanvasAskRequest body,
                                HttpServletResponse response) throws java.io.IOException {
        askService.checkPayloadLimit(request.getContentLengthLong());
        try {
            return askService.ask(userId, body);
        } catch (IllegalArgumentException e) {
            return writeJsonFallback(response, ApiResponse.fail(400, e.getMessage()));
        } catch (com.zzh.stock_calculator.common.BusinessException e) {
            return writeJsonFallback(response, ApiResponse.fail(e.getCode(), e.getMessage()));
        } catch (Exception e) {
            log.error("broker ask error: ", e);
            return writeJsonFallback(response, ApiResponse.fail(500, "服务器内部错误"));
        }
    }

    /** §3.5 监控任务开启（B 调度路径 SSOT 修订）：taskId 即 broker_monitor_task.id，占位契约 {taskId,RUNNING} 不变 */
    @PostMapping("/monitor/start")
    public ApiResponse<BrokerDtos.MonitorStartData> monitorStart(
            @RequestAttribute("authUserId") String userId,
            HttpServletRequest request,
            @RequestBody BrokerDtos.MonitorStartRequest body) {
        if (request.getContentLengthLong() > properties.getRateLimit().getPayloadLimitBytes()) {
            throw new com.zzh.stock_calculator.common.BusinessException(413,
                    "请求体超过 " + properties.getRateLimit().getPayloadLimitBytes() + " 字节上限");
        }
        return ApiResponse.success(monitorService.start(userId, body));
    }

    /** 用户预告单列表（docs/alert/design.md）：含 alert_count 进度与 band，前端管理页用 */
    @GetMapping("/monitor/list")
    public ApiResponse<BrokerDtos.MonitorListData> monitorList(
            @RequestAttribute("authUserId") String userId) {
        return ApiResponse.success(monitorService.list(userId));
    }

    /** §3.5 监控任务停止 */
    @PostMapping("/monitor/stop")
    public ApiResponse<BrokerDtos.MonitorStopData> monitorStop(
            @RequestAttribute("authUserId") String userId,
            @RequestBody BrokerDtos.MonitorStopRequest body) {
        return ApiResponse.success(monitorService.stop(userId, body == null ? null : body.getTaskId()));
    }

    /** 阶段一失败回落：手工写既有 JSON 信封并提交响应（Accept 仅 event-stream 时异常 advice 会 406） */
    private SseEmitter writeJsonFallback(HttpServletResponse response, ApiResponse<?> body) throws java.io.IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JSON_MAPPER.writeValueAsString(body));
        response.getWriter().flush();
        return null;
    }
}
