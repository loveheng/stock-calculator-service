package com.zzh.stock_calculator.service;

import com.zzh.stock_calculator.dto.Stock;
import com.zzh.stock_calculator.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockService {

    private final StockRepository stockRepository;

    /**
     * 不存在则插入，已存在则跳过
     */
    @Transactional
    public void upsertIfNotExists(Stock stock) {
        if (stockRepository.existsById(stock.getStockId())) {
            return;
        }
        stockRepository.save(stock);
        log.debug("inserted new stock, stockId={}, name={}", stock.getStockId(), stock.getName());
    }
}
