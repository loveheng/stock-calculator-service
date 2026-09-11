package com.zzh.stock_calculator.crawler;

import com.zzh.stock_calculator.crawler.mq.TaskPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * crawler 基包任务发布门面（设计文档 §4.3；拍板 C12 基包开放 API/门面上提，
 * EmbeddingSearchApi 同款 ObjectProvider 惰性解析模式）：
 * 公告域（announcement.mq 等）跨域复用 TaskPublisher 的唯一通道，
 * 不得触碰 crawler.mq 子包（Modulith 红线）。
 * <p>datasvc.mq.enabled=false 时 TaskPublisher 不装配，此处空转并记日志——
 * 快照/任务发布是数据同步面，MQ 关闭走本地回退路径，不允许影响主流程。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskDispatchApi {

    private final ObjectProvider<TaskPublisher> publisherProvider;

    /**
     * 发布任务消息（task.*：worker 竞争消费 / collector 单发单收）。
     * @return true=已投递；false=MQ 未启用（调用方走回退语义）
     */
    public boolean dispatchTask(String type, Object payload) {
        TaskPublisher publisher = publisherProvider.getIfAvailable();
        if (publisher == null) {
            log.info("datasvc.mq 未启用，跳过任务发布 type={}", type);
            return false;
        }
        publisher.dispatchTask(type, payload);
        return true;
    }

    /**
     * 发布控制消息（control.*：订阅快照等，覆盖式处理）。
     * @return true=已投递；false=MQ 未启用（调用方走回退语义）
     */
    public boolean dispatchControl(String type, Object payload) {
        TaskPublisher publisher = publisherProvider.getIfAvailable();
        if (publisher == null) {
            log.info("datasvc.mq 未启用，跳过控制消息发布 type={}", type);
            return false;
        }
        publisher.dispatchControl(type, payload);
        return true;
    }
}
