package com.zzh.stock_calculator.kg.repository;

import com.zzh.stock_calculator.kg.entity.ClsArticleKg;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * KG 任务状态表仓库（状态即游标）：发布器 findByArticleIdIn 批量取状态过滤候选，
 * 摄取端 findById 定位单一任务行。
 */
public interface ClsArticleKgRepository extends JpaRepository<ClsArticleKg, Long> {

    /** 批量取候选文章的已有状态行（发布器扫描过滤用） */
    List<ClsArticleKg> findByArticleIdIn(Collection<Long> articleIds);
}
