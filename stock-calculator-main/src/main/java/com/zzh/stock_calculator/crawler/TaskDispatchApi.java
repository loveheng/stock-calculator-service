package com.zzh.stock_calculator.crawler;

import com.zzh.stock_calculator.crawler.mq.TaskPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * crawler 基包任务发布门面（设计文档 §4.3；拍板 C12 基包开放 API/门面上提）：
 * 公告域（announcement.mq 等）跨域复用 TaskPublisher 的唯一通道，
 * 不得触碰 crawler.mq 子包（Modulith 红线）。
 */
@Service
@RequiredArgsConstructor
public class TaskDispatchApi {

    private final TaskPublisher publisher;

    /**
     * 发布任务消息（task.*：worker 竞争消费 / collector 单发单收）。
     * @return true=已投递
     */
    public boolean dispatchTask(String type, Object payload) {
        publisher.dispatchTask(type, payload);
        return true;
    }

    /**
     * 发布控制消息（control.*：订阅快照等，覆盖式处理）。
     * @return true=已投递
     */
    public boolean dispatchControl(String type, Object payload) {
        publisher.dispatchControl(type, payload);
        return true;
    }
}
