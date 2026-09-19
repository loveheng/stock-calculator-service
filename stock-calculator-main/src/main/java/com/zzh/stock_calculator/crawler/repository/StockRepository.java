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
}
