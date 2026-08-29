package com.zzh.stock_calculator.repository;

import com.zzh.stock_calculator.dto.ClsArticle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ClsArticleRepository extends JpaRepository<ClsArticle, Long> {

    // 获取时间最大（最新）的一条完整记录
    Optional<ClsArticle> findFirstByOrderByCtimeDesc();

    @Query("SELECT a.ctime  FROM ClsArticle a where a.ctime between :startTime and :endTime order by ctime asc limit 1")
    Long findHistoryMinCtime(@Param("startTime") Long startTime,@Param("endTime") Long endTime);
}
