package com.zzh.stock_calculator.service;

import com.zzh.stock_calculator.dto.TradeDraftItem;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface TradeVisionService {
    /**
     * 上层入口：校验图片、计算 Hash 并路由到缓存解析方法
     */
    List<TradeDraftItem> parseScreenshot(MultipartFile file);


}