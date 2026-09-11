package com.zzh.stock_calculator.data.announcement;

import com.rabbitmq.client.Channel;
import com.zzh.stockcalc.contract.MessageEnvelope;
import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.MqPolicy;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stockcalc.contract.message.AnnouncementDonePayload;
import com.zzh.stockcalc.contract.message.AnnouncementFailedPayload;
import com.zzh.stockcalc.contract.message.AnnouncementProcessTask;
import com.zzh.stockcalc.contract.message.SliceSelection;
import com.zzh.stockcalc.contract.message.StructureNode;
import com.zzh.stock_calculator.data.announcement.dto.ExtractedDocument;
import com.zzh.stock_calculator.data.announcement.parser.PdfTextExtractor;
import com.zzh.stock_calculator.data.announcement.parser.SlicingService;
import com.zzh.stock_calculator.data.announcement.parser.StructureTreeBuilder;
import com.zzh.stock_calculator.data.announcement.parser.TextCleaner;
import com.zzh.stock_calculator.data.announcement.service.AnnouncementDistillService;
import com.zzh.stock_calculator.data.announcement.service.GroundingValidator;
import com.zzh.stock_calculator.data.llm.AnnouncementWorkerConfig.LlmGateway;
import com.zzh.stock_calculator.data.llm.AnnouncementWorkerConfig.LlmGatewayException;
import com.zzh.stock_calculator.data.mq.ResultPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公告处理 worker（设计文档 §8 阶段 4 任务 4）：竞争消费 task.announcement.process.q，
 * 执行「下载 PDF → 抽取 → 结构树 → 阶段一路由 → 切片 → 阶段二蒸馏 → 接地校验」
 * 管线（原主服务 AnnouncementProcessService.processOne 的无 DB 化），回报
 * result.announcement.done（溯源四件套 extractorVersion/charCount/pageCount/structure/
 * selection + summary，D5 content 仅诊断随行） / result.announcement.failed（failReason
 * 分类 + errorKind 瞬时/永久）。手动 ack + prefetch=2（独立工厂）；无本地重试环——失败
 * 全量上报，主服务 fail_count 计次 + 发布端 PENDING 扫描每轮重发（D6 at-least-once +
 * 幂等摄取），worker 侧重发同 URL 靠 pdfCache 免重复下载。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "datasvc.worker", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class AnnouncementProcessWorker {

    /** 扫描件/空文本毒丸阈值（与主服务 processOne 同口径）：直接终态 SKIPPED_NO_TEXT */
    static final int MIN_TEXT_CHARS = 100;
    static final String ROUTE_LLM_STAGE1 = "llm_stage1";

    private final CninfoClient cninfoClient;
    private final TextCleaner textCleaner;
    private final PdfTextExtractor pdfTextExtractor;
    private final StructureTreeBuilder structureTreeBuilder;
    private final SlicingService slicingService;
    private final AnnouncementDistillService distillService;
    private final GroundingValidator groundingValidator;
    private final ResultPublisher resultPublisher;
    private final ObjectMapper objectMapper;

    /** adjunctUrl → PDF 字节缓存（同 URL 重发免重复下载；进程生命周期内有效） */
    private final Map<String, byte[]> pdfCache = new ConcurrentHashMap<>();

    @RabbitListener(queues = MqQueue.TASK_ANNOUNCEMENT_PROCESS,
            containerFactory = "announcementWorkerListenerFactory")
    public void onMessage(org.springframework.amqp.core.Message message,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        MessageEnvelope envelope;
        AnnouncementProcessTask task;
        try {
            envelope = objectMapper.readValue(body, MessageEnvelope.class);
            task = objectMapper.convertValue(envelope.getPayload(), AnnouncementProcessTask.class);
        } catch (Exception e) {
            // 信封不可解析=毒消息：ack 丢弃（主服务发布端下轮重发补齐，本地重试环无意义）
            log.warn("announcement task envelope unparsable, ack-dropped body={}", brief(body), e);
            ackQuietly(channel, deliveryTag);
            return;
        }
        try {
            AnnouncementDonePayload done = process(task);
            resultPublisher.publish(MessageType.RESULT_ANNOUNCEMENT_DONE, done,
                    MqPolicy.PRODUCER_WORKER, envelope.getTraceId());
            ackQuietly(channel, deliveryTag);
            log.info("announcement processed announcementId={} charCount={} summaryLen={}",
                    task.getAnnouncementId(), done.getCharCount(),
                    done.getSummary() == null ? 0 : done.getSummary().length());
        } catch (SkipTerminal skip) {
            // 毒丸终态（扫描件/接地终败）：携带终态 failReason 上报，主服务落 FAILED
            publishFailed(envelope, task, skip.failReason, skip.getMessage());
            ackQuietly(channel, deliveryTag);
        } catch (Exception e) {
            publishFailed(envelope, task, reasonOf(e), e.getMessage());
            ackQuietly(channel, deliveryTag);
        }
    }

    /** 处理管线（无 DB）：返回 done 载荷；终态毒丸抛 SkipTerminal；其余异常按瞬时语义上抛 */
    private AnnouncementDonePayload process(AnnouncementProcessTask task) throws IOException {
        byte[] pdf = pdfCache.computeIfAbsent(task.getAdjunctUrl(), cninfoClient::downloadPdf);
        ExtractedDocument doc = pdfTextExtractor.extract(pdf);
        String cleanedText = doc.getCleanedText();
        int charCount = cleanedText.codePointCount(0, cleanedText.length());
        if (charCount < MIN_TEXT_CHARS) {
            // §5 毒丸三：扫描件/空文本直接终态，终止后续 API 与 LLM 消耗
            throw new SkipTerminal("SKIPPED_NO_TEXT", "cleaned text too short: " + charCount);
        }
        List<StructureNode> nodes = structureTreeBuilder.build(doc);
        List<String> nodeIds = distillService.route(nodes);
        if (nodeIds.isEmpty()) {
            // §5 兜底：模型判定无高风险章节 → 降级取全部章/节（含 root），保底不漏
            nodeIds = fallbackNodeIds(nodes);
        }
        SliceSelection selection = slicingService.slice(cleanedText, nodes, nodeIds, ROUTE_LLM_STAGE1);
        selection.setPromptVersion(AnnouncementDistillService.PROMPT_VERSION);
        String joined = slicingService.joinSelected(cleanedText, nodes, nodeIds);
        if (joined.isBlank()) {
            // S5 实证：LLM 可能幻觉树外 nodeId，切片静默为空 → 降级取全树
            log.warn("阶段一切片为空（LLM 返回树外 nodeId？），降级取全树 announcementId={}",
                    task.getAnnouncementId());
            nodeIds = fallbackNodeIds(nodes);
            selection = slicingService.slice(cleanedText, nodes, nodeIds, ROUTE_LLM_STAGE1);
            selection.setPromptVersion(AnnouncementDistillService.PROMPT_VERSION);
            joined = slicingService.joinSelected(cleanedText, nodes, nodeIds);
        }

        String summary = distillService.distill(joined, task.getTitle(), null);

        // §4.7/D8 防线2：数值接地校验，失败回喂 mismatch 明细定向重试 1 次
        List<String> sliceTexts = slicingService.sliceTexts(cleanedText, nodes, nodeIds);
        GroundingValidator.ValidationResult grounding = groundingValidator.validate(summary, sliceTexts);
        if (!grounding.passed()) {
            log.info("接地校验失败，定向重试 announcementId={} mismatch={}",
                    task.getAnnouncementId(), grounding.mismatchDetail());
            summary = distillService.distill(joined, task.getTitle(), List.of(grounding.mismatchDetail()));
            grounding = groundingValidator.validate(summary, sliceTexts);
        }
        if (!grounding.passed()) {
            // 明细随 selection 诊断随行（§4.7），不写摘要
            selection.setGroundingMismatches(List.of(grounding.mismatchDetail()));
            throw new SkipTerminal("GROUNDING_FAIL", "接地校验终败：" + grounding.mismatchDetail());
        }

        return AnnouncementDonePayload.builder()
                .announcementId(task.getAnnouncementId())
                .extractorVersion(PdfTextExtractor.EXTRACTOR_VERSION)
                .charCount(charCount)
                .pageCount(doc.getPageCount())
                .content(cleanedText)
                .summary(summary)
                .structure(nodes)
                .selection(selection)
                .build();
    }

    /** 兕底路由：模型不可用/返回空时，取全部章/节（level<=2，含 root），保底不漏 */
    private List<String> fallbackNodeIds(List<StructureNode> nodes) {
        return nodes.stream()
                .filter(n -> n.getLevel() <= 2)
                .map(StructureNode::getNodeId)
                .toList();
    }

    private void publishFailed(MessageEnvelope envelope, AnnouncementProcessTask task,
                               String failReason, String message) {
        AnnouncementFailedPayload failed = AnnouncementFailedPayload.builder()
                .announcementId(task.getAnnouncementId())
                .failReason(failReason)
                .errorKind("SKIPPED_NO_TEXT".equals(failReason) || "GROUNDING_FAIL".equals(failReason)
                        ? "PERMANENT" : "TRANSIENT")
                .message(message == null ? "" : message)
                .build();
        resultPublisher.publish(MessageType.RESULT_ANNOUNCEMENT_FAILED, failed,
                MqPolicy.PRODUCER_WORKER, envelope.getTraceId());
    }

    /** 失败分类（与主服务 reasonOf 同口径；枚举值经契约字符串随行） */
    private String reasonOf(Exception e) {
        if (e instanceof org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            return "SKIPPED_ENCRYPTED";
        }
        if (e instanceof GroundingValidator.GroundingFailException) {
            return "GROUNDING_FAIL";
        }
        if (e instanceof AnnouncementDistillService.LlmRouteException
                || e instanceof LlmGatewayException) {
            return "LLM_ROUTE_FAIL";
        }
        if (e instanceof CninfoClient.CninfoHttpException || e instanceof CninfoClient.CninfoDownloadException
                || e instanceof IllegalArgumentException) {
            return "DOWNLOAD_FAIL";
        }
        return "PARSE_FAIL";
    }

    private void ackQuietly(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            log.warn("ack failed", e);
        }
    }

    private String brief(String body) {
        return body.length() <= 200 ? body : body.substring(0, 200) + "...";
    }

    /** 终态毒丸：SKIPPED_NO_TEXT / GROUNDING_FAIL（重试无意义，直接终态语义） */
    private static class SkipTerminal extends RuntimeException {
        final String failReason;

        SkipTerminal(String failReason, String message) {
            super(message);
            this.failReason = failReason;
        }
    }
}
