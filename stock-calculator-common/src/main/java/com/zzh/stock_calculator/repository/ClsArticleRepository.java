package com.zzh.stock_calculator.repository;

import com.zzh.stock_calculator.dto.ClsArticle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ClsArticleRepository extends JpaRepository<ClsArticle, Long> {

    // 获取时间最大（最新）的一条完整记录
    Optional<ClsArticle> findFirstByOrderByCtimeDesc();
}
