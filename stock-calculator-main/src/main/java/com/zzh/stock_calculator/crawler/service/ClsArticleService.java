package com.zzh.stock_calculator.crawler.service;
import com.zzh.stock_calculator.crawler.entity.ClsArticle;
import com.zzh.stock_calculator.crawler.entity.ClsArticleStock;
import com.zzh.stock_calculator.crawler.entity.ClsArticleSubject;
import com.zzh.stock_calculator.crawler.entity.ClsSubject;
import com.zzh.stock_calculator.crawler.entity.Stock;
import com.zzh.stock_calculator.crawler.event.ArticleSavedEvent;
import com.zzh.stock_calculator.crawler.repository.ClsArticleRepository;
import com.zzh.stock_calculator.crawler.repository.ClsArticleStockRepository;
import com.zzh.stock_calculator.crawler.repository.ClsArticleSubjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClsArticleService {

    private final ClsArticleRepository articleRepository;
    private final ClsArticleSubjectRepository subjectRepository;
    private final ClsArticleStockRepository stockRepository;
    private final StockService stockService;
    private final ClsSubjectService clsSubjectService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public boolean saveIfNotExists(ClsArticle article) {
        if (articleRepository.existsById(article.getId())) {
            return false;
        }
        articleRepository.save(article);
        log.info("saved new article, id={}", article.getId());
        return true;
    }

    /**
     * 事务内先 upsert 字典表，再写文章 + 关联表
     */
    @Transactional
    public boolean saveArticleWithRelations(ClsArticle article,
                                            List<ClsArticleSubject> subjects,
                                            List<ClsArticleStock> stocks,
                                            List<Stock> stockDicts,
                                            List<ClsSubject> subjectDicts) {
        if (!saveIfNotExists(article)) {
            return false;
        }

        // 1. upsert 股票/题材字典
        if (stockDicts != null) {
            stockDicts.forEach(stockService::upsertIfNotExists);
        }
        if (subjectDicts != null) {
            subjectDicts.forEach(clsSubjectService::upsertIfNotExists);
        }

        // 2. 写入关联表
        if (subjects != null && !subjects.isEmpty()) {
            subjectRepository.saveAll(subjects);
        }
        if (stocks != null && !stocks.isEmpty()) {
            stockRepository.saveAll(stocks);
        }

        log.info("saved article(id={}) with {} subjects, {} stocks",
                article.getId(),
                subjects != null ? subjects.size() : 0,
                stocks != null ? stocks.size() : 0);

        // 增量向量化触发点（设计文档 §4.5，唯一侵入点）：事务提交后由监听器异步消费
        eventPublisher.publishEvent(ArticleSavedEvent.builder()
                .articleId(article.getId())
                .ctime(article.getCtime())
                .build());
        return true;
    }

    public ClsArticle getMaxCtimeByClsArticle() {
        Optional<ClsArticle> firstByOrderByCtimeDesc = articleRepository.findFirstByOrderByCtimeDesc();
        return firstByOrderByCtimeDesc.orElse(null);
    }

    public Long findHistoryMinCtime(long startTime, long endTime) {
        return articleRepository.findHistoryMinCtime(startTime, endTime);
    };


}
