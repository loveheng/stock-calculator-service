package com.zzh.stock_calculator.controller;

import com.zzh.stock_calculator.common.ApiResponse;
import com.zzh.stock_calculator.dto.TradeDraftItem;
import com.zzh.stock_calculator.service.impl.GeminiTradeVisionServiceImpl;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;


@CrossOrigin(origins = "*") // 允许前端直接跨域调用
@RestController
@RequestMapping("/api/import")
@RequiredArgsConstructor
public class ImportController {


    private final GeminiTradeVisionServiceImpl tradeVisionService;

    @PostMapping("/ocr-parse")
    public ApiResponse<List<TradeDraftItem>> parseTradeScreenshot(@RequestParam("file") MultipartFile file) {
        List<TradeDraftItem> result = tradeVisionService.parseScreenshot(file);
        return ApiResponse.success(result);
    }
}
