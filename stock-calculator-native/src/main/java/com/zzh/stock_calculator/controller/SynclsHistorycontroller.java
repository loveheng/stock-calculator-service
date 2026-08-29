package com.zzh.stock_calculator.controller;

import com.zzh.stock_calculator.task.HistoryClsDayTask;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/sync")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "crawler", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SynclsHistorycontroller {

    private final HistoryClsDayTask historyClsDayTask;

    @PostMapping("/history/start")
    public String startHistorySync(@RequestParam(defaultValue = "1787903769") long startTime,
                                   @RequestParam(defaultValue = "1693320189") long endTime) {
        historyClsDayTask.startHistorySync(startTime, endTime);
        return "历史数据拉取任务已在后台启动";
    }

    @PostMapping("/history/stop")
    public String stopHistorySync() {
        historyClsDayTask.stopSync();
        return "正在停止历史数据拉取任务";
    }

}
