package com.zzh.stock_calculator.crawler.repository;
import com.zzh.stock_calculator.crawler.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockRepository extends JpaRepository<Stock, String> {

    /** 名称含指定子串（短查询实体判定：query 是字典名的子串，如「闻泰」⊂「闻泰科技」） */
    List<Stock> findByNameContaining(String name);

    /** 曾用名含指定子串（更名股票的短查询实体判定） */
    List<Stock> findByOldNameContaining(String oldName);
}
