package com.zzh.stock_calculator.crawler.repository;
import com.zzh.stock_calculator.crawler.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StockRepository extends JpaRepository<Stock, String> {
}
