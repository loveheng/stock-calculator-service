package com.zzh.stock_calculator.task;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class ClsSearchTask {

    // 任务运行状态控制标记（默认为 false）
    private final AtomicBoolean isSearchRunning = new AtomicBoolean(false);


    @Async
    public void getClsDataByCode(String code) {


    }


}
