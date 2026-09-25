package com.zzh.stock_calculator.crawler.repository;
import com.zzh.stock_calculator.crawler.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockRepository extends JpaRepository<Stock, String> {

    /** 名称含指定子串（短查询实体判定：query 是字典名的子串，如「闻泰」⊂「闻泰科技」） */
    List<Stock> findByNameContaining(String name);

    /** 名称精确命中（news-kg 字典锚点解析；findByName 是 Derived 关键字，显式 findFirst 消歧） */
    Optional<Stock> findFirstByName(String name);

    /** 旧名精确命中（字典锚点兜底，覆盖曾用名实体） */
    Optional<Stock> findFirstByOldName(String oldName);

    /** 曾用名含指定子串（更名股票的短查询实体判定） */
    List<Stock> findByOldNameContaining(String oldName);

    /** 6 位码双形态匹配（字典形态混杂：沪深 sh600745 前缀尾部 / 北交所 920000.BJ 后缀头部 "920000."） */
    boolean existsByStockIdEndingWithOrStockIdStartingWith(String endingSuffix, String startingPrefix);
}
