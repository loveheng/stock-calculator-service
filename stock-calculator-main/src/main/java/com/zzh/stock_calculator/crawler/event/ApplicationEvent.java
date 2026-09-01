package com.zzh.stock_calculator.crawler.event;
import com.zzh.stock_calculator.crawler.entity.ClsArticle;
import com.zzh.stock_calculator.crawler.service.TaskService;
import com.zzh.stock_calculator.crawler.util.ClsDayTaskHelp;
import com.zzh.stock_calculator.crawler.util.ParseDataUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "crawler", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ApplicationEvent {

    private final TaskService taskService;

    /**
     * 系统启动时自动执行补录。
     * 监听器内任何异常都不允许向外传播，否则会中断 SpringApplication.run 带崩整个应用。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {

        try {
            log.info(">>> 应用已就绪，补录任务将在 10 秒后开始执行...");
            TimeUnit.SECONDS.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("补录任务延迟等待被中断，直接返回", e);
            return;
        }

        // 1. 查询本地数据库中最新的一条文章的时间戳，作为补录对齐基准
        Long tableTime;
        try {
            ClsArticle maxCtimeByClsArticle = taskService.getMaxCtimeByClsArticle();
            if (maxCtimeByClsArticle == null) {
                // 本地是空库（首次冷启动），没有对齐基准，交给定时任务按增量模式抓取
                log.info(">>> 本地库为空，跳过启动补录，等待定时任务增量抓取。");
                return;
            }
            tableTime = maxCtimeByClsArticle.getCtime();
        } catch (Exception e) {
            log.error(">>> 启动补录查询本地最大时间失败，跳过本次补录，等待定时任务接管。", e);
            return;
        }

        log.info(">>> 开始检查并补偿停机期间的财联社数据，本地最大时间戳: {}", tableTime);

        long tempTime = Instant.now().getEpochSecond();
        int count = 0;      // 插入条数
        int forCount = 0;   // 循环次数
        int consecutiveFailures = 0;

        while (true) {
            try {
                forCount++;
                List<?> rollData = taskService.getRollData(tempTime, 1);

                for (Object item : rollData) {
                    if (!(item instanceof Map<?, ?> raw)) {
                        continue;
                    }
                    Map<String, Object> stringObjectMap = ClsDayTaskHelp.coerceMap(raw);
                    long time = ParseDataUtil.toLong(stringObjectMap.get("ctime"), 0);

                    // 获取下一次查询的时间起点
                    if (time <= tempTime) {
                        tempTime = time;
                    }
                }

                boolean chainConnected = tempTime <= tableTime;

                // 注意：即使数据链已接上，本批数据仍要落库（批内含缺口数据）
                count = taskService.processData(rollData);

                // 数据链接上 / 无新增 / 循环达上限，结束补录
                if (chainConnected || count == 0 || forCount >= 10) {
                    break;
                }

                consecutiveFailures = 0;

                // 避免高频请求，微休眠
                long sleepTime = ThreadLocalRandom.current().nextLong(5000, 7000);
                TimeUnit.MILLISECONDS.sleep(sleepTime);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("启动补录被中断，安全退出", e);
                return;
            } catch (Exception e) {
                consecutiveFailures++;
                log.warn("启动补录抓取失败，连续失败次数: {}", consecutiveFailures, e);
                if (consecutiveFailures >= 5) {
                    log.error(">>> 启动补录连续 5 次失败，提前终止，等待定时任务接管。");
                    return;
                }
                try {
                    // 逐次递增的退避：10s / 20s / 30s / 40s
                    TimeUnit.SECONDS.sleep(10L * consecutiveFailures);
                    continue;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        log.info(">>> 停机期间数据补偿完成！进入日常定时增量抓取模式。");
    }
}
