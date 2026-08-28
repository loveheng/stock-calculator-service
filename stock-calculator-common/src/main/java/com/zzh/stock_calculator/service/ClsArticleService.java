package com.zzh.stock_calculator.service;

import com.zzh.stock_calculator.dto.ClsArticle;
import com.zzh.stock_calculator.dto.ClsArticleStock;
import com.zzh.stock_calculator.dto.ClsArticleSubject;
import com.zzh.stock_calculator.dto.ClsSubject;
import com.zzh.stock_calculator.dto.Stock;
import com.zzh.stock_calculator.repository.ClsArticleRepository;
import com.zzh.stock_calculator.repository.ClsArticleStockRepository;
import com.zzh.stock_calculator.repository.ClsArticleSubjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClsArticleService {

    private final ClsArticleRepository articleRepository;
    private final ClsArticleSubjectRepository subjectRepository;
    private final ClsArticleStockRepository stockRepository;
    private final StockService stockService;
    private final ClsSubjectService clsSubjectService;

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
        return true;
    }
}