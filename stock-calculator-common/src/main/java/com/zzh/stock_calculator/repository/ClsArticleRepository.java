package com.zzh.stock_calculator.repository;

import com.zzh.stock_calculator.dto.ClsArticle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClsArticleRepository extends JpaRepository<ClsArticle, Long> {
}
