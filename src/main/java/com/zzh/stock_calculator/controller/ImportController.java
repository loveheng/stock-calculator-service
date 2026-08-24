package com.zzh.stock_calculator.controller;

import com.zzh.stock_calculator.dto.TradeDraftItem;
import com.zzh.stock_calculator.service.TradeVisionService;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;


@CrossOrigin(origins = "*") // 允许前端直接跨域调用
@RestController
@RequestMapping("/api/import")
@RequiredArgsConstructor
public class ImportController {


    private final TradeVisionService tradeVisionService;

    @PostMapping("/ocr-parse")
    public ResponseEntity<?> parseScreenshot(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "上传文件不能为空"));
        }

        List<TradeDraftItem> items = tradeVisionService.parseScreenshot(file);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "count", items.size(),
                "data", items
        ));
    }
}
