package com.zzh.stock_calculator.crawler.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文章入库成功事件（设计文档 §4.5）。
 * 由 ClsArticleService.saveArticleWithRelations 在事务提交前发布，
 * 监听方 @TransactionalEventListener(AFTER_COMMIT) 保证仅提交后消费。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArticleSavedEvent {

    private Long articleId;

    /** 原始发布时间戳（秒） */
    private Long ctime;
}
