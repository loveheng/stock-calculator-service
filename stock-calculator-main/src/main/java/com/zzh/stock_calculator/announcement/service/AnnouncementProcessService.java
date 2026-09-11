package com.zzh.stock_calculator.announcement.service;

import com.zzh.stock_calculator.announcement.client.CninfoClient;
import com.zzh.stock_calculator.announcement.config.AnnouncementProperties;
import com.zzh.stock_calculator.announcement.dto.ExtractedDocument;
import com.zzh.stockcalc.contract.message.SliceSelection;
import com.zzh.stockcalc.contract.message.StructureNode;
import com.zzh.stock_calculator.announcement.entity.Announcement;
import com.zzh.stock_calculator.announcement.entity.AnnouncementContent;
import com.zzh.stock_calculator.announcement.entity.AnnouncementFailReason;
import com.zzh.stock_calculator.announcement.entity.AnnouncementStatus;
import com.zzh.stock_calculator.announcement.parser.PdfTextExtractor;
import com.zzh.stock_calculator.announcement.parser.SlicingService;
import com.zzh.stock_calculator.announcement.parser.StructureTreeBuilder;
import com.zzh.stock_calculator.announcement.repository.AnnouncementContentRepository;
import com.zzh.stock_calculator.announcement.repository.AnnouncementRepository;
import com.zzh.stock_calculator.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

/**
 * 公告批处理（设计文档 §4.5~§4.8）：下载 → 内存抽取 → 建树 → 阶段一路由 → 切片 →
 * 阶段二蒸馏 → 数值接地校验（§4.7/D8）→ 溯源落库 → 向量化（§4.8）→ DONE。
 * 断点续传：summary 非空仅补向量化；落库顺序 content 先行 → summary 次之 → embedding 最后
 * （content 先写崩溃后全量重做；summary 先写崩溃后走断点续传但 content 缺失——顺序不可换）。
 * 状态机 D9：瞬时失败计次（LLM 路由/蒸馏失败、网络），PERMANENT/密锁/接地终败/达限落 FAILED；
 * RATE_LIMITED 熔断本批。无 @Transactional：下载/解析网络与文件 IO 不进事务（红线）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementProcessService {

    /** 扫描件/空文本判定阈值（§5 兜底防线）：code point 数 */
    private static final int MIN_TEXT_CHARS = 100;

    /** §4.4 路由来源标记：LLM 阶段一选中（含空结果降级取全树） */
    private static final String ROUTE_LLM_STAGE1 = "llm_stage1";

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementContentRepository contentRepository;
    private final CninfoClient cninfoClient;
    private final PdfTextExtractor pdfTextExtractor;
    private final StructureTreeBuilder structureTreeBuilder;
    private final SlicingService slicingService;
    private final AnnouncementDistillService distillService;
    private final GroundingValidator groundingValidator;
    private final AnnouncementEmbeddingService embeddingService;
    private final AnnouncementProperties properties;
    private final ObjectMapper objectMapper;

    /** PENDING 批消费入口（AnnouncementProcessTask 调度）：近端优先 50 条/批 */
    public int processNextBatch() {
        if (!properties.getProcess().isEnabled()) {
            return 0;
        }
        List<Announcement> batch = announcementRepository
                .findTop50ByStatusOrderBySeDateDescIdDesc(AnnouncementStatus.PENDING);
        int processed = 0;
        for (Announcement announcement : batch) {
            try {
                processOne(announcement);
                processed++;
            } catch (Exception e) {
                if (failOne(announcement, e) == CninfoClient.CninfoErrorKind.RATE_LIMITED) {
                    log.warn("CNINFO 限流，本批提前终止（剩余 {} 条下轮重试）", batch.size() - processed - 1);
                    break;
                }
            }
        }
        return processed;
    }

    private void processOne(Announcement announcement) throws IOException {
        // 断点续传：蒸馏已落摘要（上轮 embedding 外部故障/额度拒绝）→ 仅补向量化
        if (StringUtils.hasText(announcement.getSummary())) {
            embeddingService.processAnnouncement(announcement.getId());
            return;
        }
        byte[] pdf = cninfoClient.downloadPdf(announcement.getAdjunctUrl());
        ExtractedDocument doc = pdfTextExtractor.extract(pdf);
        String cleanedText = doc.getCleanedText();
        int charCount = cleanedText.codePointCount(0, cleanedText.length());
        if (charCount < MIN_TEXT_CHARS) {
            // §5 毒丸三：扫描件/空文本直接终态，终止后续 API 与 LLM 消耗
            markTerminal(announcement, AnnouncementFailReason.SKIPPED_NO_TEXT);
            return;
        }
        List<StructureNode> nodes = structureTreeBuilder.build(doc);
        // §4.4 阶段一：LLM 筛选高风险标题，仅回传 nodeId 数组；LlmRouteException/503 向上计次
        List<String> nodeIds = distillService.route(nodes);
        if (nodeIds.isEmpty()) {
            // §5 兜底：模型判定无高风险章节 → 降级取全部章/节（含 root），保底不漏
            nodeIds = fallbackNodeIds(nodes);
        }
        SliceSelection selection = slicingService.slice(cleanedText, nodes, nodeIds, ROUTE_LLM_STAGE1);
        selection.setPromptVersion(AnnouncementDistillService.PROMPT_VERSION);
        String joined = slicingService.joinSelected(cleanedText, nodes, nodeIds);
        if (joined.isBlank()) {
            // S5 实证：LLM 可能幻觉树外 nodeId（回传 ["1"] 而树上为 "0-x"），切片静默为空 → 降级取全树
            log.warn("阶段一切片为空（LLM 返回树外 nodeId？route={}），降级取全树 id={}",
                    nodeIds, announcement.getId());
            nodeIds = fallbackNodeIds(nodes);
            selection = slicingService.slice(cleanedText, nodes, nodeIds, ROUTE_LLM_STAGE1);
            selection.setPromptVersion(AnnouncementDistillService.PROMPT_VERSION);
            joined = slicingService.joinSelected(cleanedText, nodes, nodeIds);
        }
        log.info("阶段一切片完成 id={} selected={} joinedLen={}",
                announcement.getId(), nodeIds.size(), joined.length());

        // §4.6 阶段二：蒸馏 200~300 字纯事实摘要
        String summary = distillService.distill(joined, announcement.getTitle(), null);

        // §4.7/D8 防线2：数值接地校验，失败回喂 mismatch 明细定向重试 1 次
        List<String> sliceTexts = slicingService.sliceTexts(cleanedText, nodes, nodeIds);
        GroundingValidator.ValidationResult grounding = groundingValidator.validate(summary, sliceTexts);
        if (!grounding.passed()) {
            log.info("接地校验失败，定向重试 id={} mismatch={}", announcement.getId(), grounding.mismatchDetail());
            summary = distillService.distill(joined, announcement.getTitle(), List.of(grounding.mismatchDetail()));
            grounding = groundingValidator.validate(summary, sliceTexts);
        }
        if (!grounding.passed()) {
            // 明细落 selection_json，不落摘要不落向量（§4.7）
            selection.setGroundingMismatches(List.of(grounding.mismatchDetail()));
            upsertContent(announcement.getId(), doc, charCount, nodes, selection);
            throw new GroundingValidator.GroundingFailException("接地校验终败：" + grounding.mismatchDetail());
        }

        // 落库顺序：content（溯源）→ summary（断点续传锚点）→ embedding（DONE），崩溃安全
        upsertContent(announcement.getId(), doc, charCount, nodes, selection);
        announcement.setSummary(summary);
        announcementRepository.save(announcement);
        log.info("公告蒸馏完成 id={} title={} summaryLen={} selected={} joinedLen={}",
                announcement.getId(), announcement.getTitle(), summary.length(), nodeIds.size(), joined.length());
        embeddingService.processAnnouncement(announcement.getId());
    }

    /** 降级选择：全部章/节（level<=2）；纯三级异常树时取全部节点 */
    private static List<String> fallbackNodeIds(List<StructureNode> nodes) {
        List<String> ids = nodes.stream().filter(n -> n.getLevel() <= 2)
                .map(StructureNode::getNodeId).toList();
        return ids.isEmpty() ? nodes.stream().map(StructureNode::getNodeId).toList() : ids;
    }

    /** 1:1 溯源 upsert：结构树/切片选择 JSONB + 重放三件套（extractorVersion/charCount/pageCount） */
    private void upsertContent(Long announcementId, ExtractedDocument doc, int charCount,
                               List<StructureNode> nodes, SliceSelection selection) {
        AnnouncementContent content = contentRepository.findById(announcementId)
                .orElseGet(() -> AnnouncementContent.builder().announcementId(announcementId).build());
        content.setExtractorVersion(PdfTextExtractor.EXTRACTOR_VERSION);
        content.setCharCount(charCount);
        content.setPageCount(doc.getPageCount());
        content.setStructureJson(writeJson(nodes));
        content.setSelectionJson(writeJson(selection));
        contentRepository.save(content);
    }

    private String writeJson(Object value) {
        // Jackson 3（tools.jackson）抛非受检异常，无需 try/throws
        return objectMapper.writeValueAsString(value);
    }

    /** 失败分类落账：返回错误类别供批级熔断判断（RATE_LIMITED → break） */
    private CninfoClient.CninfoErrorKind failOne(Announcement announcement, Exception e) {
        CninfoClient.CninfoErrorKind kind = CninfoClient.classify(e);
        AnnouncementFailReason reason = reasonOf(e);
        // InvalidPasswordException 是 IOException 子类，必须先判（密锁 = 永久跳过）
        // GroundingFailException：接地终败，重试无意义，直接终态（§4.7）
        boolean terminalNow = e instanceof InvalidPasswordException
                || e instanceof GroundingValidator.GroundingFailException
                || kind == CninfoClient.CninfoErrorKind.PERMANENT;
        int failCount = announcement.getFailCount() == null ? 1 : announcement.getFailCount() + 1;
        announcement.setFailCount(failCount);
        if (terminalNow || failCount >= properties.getProcess().getMaxFailAttempts()) {
            announcement.setStatus(AnnouncementStatus.FAILED);
            announcement.setStatusReason(reason);
            log.warn("公告落终态 id={} reason={} err={}", announcement.getId(), reason, e.getMessage());
        } else {
            log.info("公告瞬时失败计次 id={} failCount={} kind={} err={}",
                    announcement.getId(), failCount, kind, e.getMessage());
        }
        announcementRepository.save(announcement);
        return kind;
    }

    private AnnouncementFailReason reasonOf(Exception e) {
        if (e instanceof InvalidPasswordException) {
            return AnnouncementFailReason.SKIPPED_ENCRYPTED;
        }
        if (e instanceof GroundingValidator.GroundingFailException) {
            return AnnouncementFailReason.GROUNDING_FAIL;
        }
        if (e instanceof AnnouncementDistillService.LlmRouteException || e instanceof BusinessException) {
            return AnnouncementFailReason.LLM_ROUTE_FAIL;
        }
        if (e instanceof CninfoClient.CninfoHttpException || e instanceof CninfoClient.CninfoDownloadException
                || e instanceof IllegalArgumentException) {
            return AnnouncementFailReason.DOWNLOAD_FAIL;
        }
        return AnnouncementFailReason.PARSE_FAIL;
    }

    private void markTerminal(Announcement announcement, AnnouncementFailReason reason) {
        announcement.setStatus(AnnouncementStatus.FAILED);
        announcement.setStatusReason(reason);
        announcementRepository.save(announcement);
    }
}
