package com.zzh.stock_calculator.repository;

import com.zzh.stock_calculator.dto.ClsArticleStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClsArticleStockRepository extends JpaRepository<ClsArticleStock, Long> {
}
