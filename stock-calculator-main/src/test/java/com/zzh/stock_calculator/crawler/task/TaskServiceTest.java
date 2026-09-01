package com.zzh.stock_calculator.crawler.task;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class TaskServiceTest {

    @Autowired
    private ClsDayTask taskService;

    @Test
    void testFixedDelayTask() {
        taskService.fixedDelayTask();
    }
}
