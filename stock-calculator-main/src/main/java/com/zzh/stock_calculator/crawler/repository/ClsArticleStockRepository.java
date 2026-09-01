package com.zzh.stock_calculator.crawler.repository;
import com.zzh.stock_calculator.crawler.entity.ClsArticleStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ClsArticleStockRepository extends JpaRepository<ClsArticleStock, Long> {
}
