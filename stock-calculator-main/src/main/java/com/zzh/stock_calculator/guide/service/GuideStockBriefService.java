package com.zzh.stock_calculator.guide.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.common.McpDispatchClient;
import com.zzh.stock_calculator.crawler.ClsArticleQueryApi;
import com.zzh.stock_calculator.crawler.ClsArticleQueryApi.ArticleHead;
import com.zzh.stock_calculator.crawler.ClsArticleQueryApi.SubjectTag;
import com.zzh.stock_calculator.crawler.StockDirectoryApi;
import com.zzh.stock_calculator.guide.dto.GuideDtos.AnnouncementItem;
import com.zzh.stock_calculator.guide.dto.GuideDtos.ArticleBrief;
import com.zzh.stock_calculator.guide.dto.GuideDtos.LevelBand;
import com.zzh.stock_calculator.guide.dto.GuideDtos.MentionBlock;
import com.zzh.stock_calculator.guide.dto.GuideDtos.StockBriefResponse;
import com.zzh.stock_calculator.guide.dto.GuideDtos.SubjectItem;
import com.zzh.stock_calculator.guide.dto.GuideDtos.TechSnapshot;
import com.zzh.stock_calculator.search.StockProfileApi;
import com.zzh.stock_calculator.search.StockProfileApi.StockProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 选股引导 Step2：个股引导档案聚合（docs/guide/design.md §三.1）。
 * 纯 main 侧只读聚合：电报提及（计数+文章头）+ 题材归属（反向两跳）+ 公告蒸馏摘要
 * + 技术面快照（P2 已落地：经 dispatch 调 mcp stock_analysis/stock_levels，
 * dispatch 不可用时降级为 null，主流程不受影响）。提醒登记联动为 P2（文案指路）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuideStockBriefService {

    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 30;
    private static final int MAX_STOCK_ID_LENGTH = 32;
    private static final int MENTION_ARTICLE_LIMIT = 5;
    private static final int SUBJECT_LIMIT = 5;
    private static final long SECONDS_PER_DAY = 86400L;
    private static final int SNAPSHOT_SIGNAL_LIMIT = 8;
    private static final String TRACE_GUIDE_BRIEF = "guide-brief";

    /** 动作建议（首位红线②）：技术面深挖与提醒登记为下一步指引 */
    static final List<String> DEFAULT_NEXT_STEPS = List.of(
            "techSnapshot 是日线级粗粒度快照，深挖形态可让 AI 算具体指标或画 K 线复核",
            "确认关注价值后说「帮我设个提醒」跟踪后续（提醒登记联动待 P2 设计）",
            "需要回溯消息来源时，可引用 clsMention.articles 里的电报标题追问");

    private final ClsArticleQueryApi clsArticleQueryApi;
    private final StockDirectoryApi stockDirectoryApi;
    private final StockProfileApi stockProfileApi;
    private final McpDispatchClient dispatchClient;

    public StockBriefResponse brief(String stockId, Integer days) {
        if (stockId == null || stockId.isBlank()) {
            throw new BusinessException(400, "stockId 不能为空");
        }
        String code = stockId.trim();
        if (code.length() > MAX_STOCK_ID_LENGTH) {
            throw new BusinessException(400, "stockId 非法");
        }
        // 裸 6 位/腾讯形态输入归一化到字典键（D11）：全码原样通过，未收录保持原值（行为同前，不报错）
        String dictKey = stockDirectoryApi.resolveDictKey(code);
        if (dictKey != null) {
            code = dictKey;
        }
        int windowDays = clampDays(days);
        long sinceCtime = Instant.now().getEpochSecond() - windowDays * SECONDS_PER_DAY;

        long mentionCount = clsArticleQueryApi.countByStockCodeSince(code, sinceCtime);
        List<ArticleHead> heads =
                clsArticleQueryApi.recentArticlesByStock(code, sinceCtime, MENTION_ARTICLE_LIMIT);
        List<SubjectTag> subjects =
                clsArticleQueryApi.subjectsByStockSince(code, sinceCtime, SUBJECT_LIMIT);
        StockProfile profile = stockProfileApi.profile(code);
        String stockName = firstNonBlank(profile.stockName(), stockDirectoryApi.nameByCode(code));
        TechSnapshot techSnapshot = fetchTechSnapshot(code);

        log.info("guide stock-brief: stockId={}, days={}, mentions={}, subjects={}, announcements={}",
                code, windowDays, mentionCount, subjects.size(), profile.announcements().size());
        return StockBriefResponse.builder()
                .nextSteps(DEFAULT_NEXT_STEPS)
                .stockId(code)
                .stockName(stockName == null ? "" : stockName)
                .clsMention(MentionBlock.builder()
                        .count(mentionCount)
                        .articles(toBriefs(heads))
                        .build())
                .subjects(subjects.stream()
                        .map(tag -> SubjectItem.builder()
                                .subjectId(tag.subjectId())
                                .subjectName(tag.subjectName())
                                .articleCount(tag.articleCount())
                                .build())
                        .toList())
                .announcements(profile.announcements().stream()
                        .map(a -> AnnouncementItem.builder()
                                .annDate(a.annDate())
                                .title(a.title())
                                .summary(a.summary())
                                .build())
                        .toList())
                .techSnapshot(techSnapshot)
                .build();
    }

    /** 技术面快照（P2）：经 dispatch 调 mcp 指标/位带工具，粗粒度取值 */
    // DEGRADE: dispatch/mcp 不可用时快照缺席（null），档案主流程不受影响
    private TechSnapshot fetchTechSnapshot(String code) {
        try {
            JsonNode analysis = dispatchClient.invokeTool("stock_analysis",
                    Map.of("stock", code), TRACE_GUIDE_BRIEF);
            JsonNode levels = dispatchClient.invokeTool("stock_levels",
                    Map.of("stock", code), TRACE_GUIDE_BRIEF);
            if (analysis.has("error") || !analysis.hasNonNull("lastDate")) {
                log.warn("[DEGRADE] guide-tech-snapshot 分析不可用 stockId={} reason={}",
                        code, analysis.path("error").asString("missing-lastDate"));
                return null;
            }
            return TechSnapshot.builder()
                    .lastDate(analysis.path("lastDate").asString(null))
                    .lastClose(analysis.path("lastClose").asDouble())
                    .changePct(analysis.path("changePct").asDouble())
                    .signals(toSignalList(analysis.get("signals")))
                    .nearestSupport(firstBand(levels.get("supports")))
                    .nearestResistance(firstBand(levels.get("resistances")))
                    .build();
        } catch (RuntimeException e) {
            log.warn("[DEGRADE] guide-tech-snapshot 通道不可用 stockId={} err={}", code, e.getMessage());
            return null;
        }
    }

    private static List<String> toSignalList(JsonNode signals) {
        if (signals == null || !signals.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode s : signals) {
            if (out.size() >= SNAPSHOT_SIGNAL_LIMIT) {
                break;
            }
            out.add(s.asString());
        }
        return out;
    }

    /** 最近位带 = 首档（工具侧按现价由近及远排序） */
    private static LevelBand firstBand(JsonNode bands) {
        if (bands == null || !bands.isArray() || bands.isEmpty()) {
            return null;
        }
        JsonNode band = bands.get(0);
        return LevelBand.builder()
                .priceLow(band.path("priceLow").asDouble())
                .priceHigh(band.path("priceHigh").asDouble())
                .type(band.path("type").asString(null))
                .distPct(band.path("distPct").asDouble())
                .build();
    }

    private static List<ArticleBrief> toBriefs(List<ArticleHead> heads) {
        return heads.stream()
                .map(head -> ArticleBrief.builder()
                        .articleId(head.articleId())
                        .title(head.title())
                        .ctime(head.ctime())
                        .build())
                .toList();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }

    private static int clampDays(Integer days) {
        if (days == null) {
            return DEFAULT_DAYS;
        }
        return Math.min(Math.max(days, 1), MAX_DAYS);
    }
}
