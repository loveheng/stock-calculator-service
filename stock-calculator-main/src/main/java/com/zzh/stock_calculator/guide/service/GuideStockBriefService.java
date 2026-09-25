package com.zzh.stock_calculator.guide.service;

import com.zzh.stock_calculator.common.BusinessException;
import com.zzh.stock_calculator.crawler.ClsArticleQueryApi;
import com.zzh.stock_calculator.crawler.ClsArticleQueryApi.ArticleHead;
import com.zzh.stock_calculator.crawler.ClsArticleQueryApi.SubjectTag;
import com.zzh.stock_calculator.crawler.StockDirectoryApi;
import com.zzh.stock_calculator.guide.dto.GuideDtos.AnnouncementItem;
import com.zzh.stock_calculator.guide.dto.GuideDtos.ArticleBrief;
import com.zzh.stock_calculator.guide.dto.GuideDtos.MentionBlock;
import com.zzh.stock_calculator.guide.dto.GuideDtos.StockBriefResponse;
import com.zzh.stock_calculator.guide.dto.GuideDtos.SubjectItem;
import com.zzh.stock_calculator.search.StockProfileApi;
import com.zzh.stock_calculator.search.StockProfileApi.StockProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 选股引导 Step2：个股引导档案聚合（docs/guide/design.md §三.1）。
 * 纯 main 侧只读聚合：电报提及（计数+文章头）+ 题材归属（反向两跳）+ 公告蒸馏摘要。
 * 技术面/提醒为 P2（nextSteps 文案指路，不经本服务调用外部工具）。
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

    /** 动作建议（P2 前为文案指路：技术面走经纪人工具，提醒走自然语言登记） */
    static final List<String> DEFAULT_NEXT_STEPS = List.of(
            "让 AI 算技术指标核实形态（聊天里说「算下 XX 的形态」即经编排调用经纪人工具）",
            "确认关注价值后说「帮我设个提醒」跟踪后续（P2 前为人工动作指引）",
            "需要回溯消息来源时，可引用 clsMention.articles 里的电报标题追问");

    private final ClsArticleQueryApi clsArticleQueryApi;
    private final StockDirectoryApi stockDirectoryApi;
    private final StockProfileApi stockProfileApi;

    public StockBriefResponse brief(String stockId, Integer days) {
        if (stockId == null || stockId.isBlank()) {
            throw new BusinessException(400, "stockId 不能为空");
        }
        String code = stockId.trim();
        if (code.length() > MAX_STOCK_ID_LENGTH) {
            throw new BusinessException(400, "stockId 非法");
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
