package com.zzh.stock_calculator.data.announcement;

import com.rabbitmq.client.Channel;
import com.zzh.stockcalc.contract.MqKey;
import com.zzh.stockcalc.contract.MqQueue;
import com.zzh.stockcalc.contract.message.SubscriptionSnapshotPayload;
import com.zzh.stock_calculator.data.announcement.SubscriptionSnapshotCache.SnapshotState;
import com.zzh.stock_calculator.data.config.PullLoopProperties;
import com.zzh.stock_calculator.data.mq.PullLoopRenewer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 公告常态采集消费者（docs/pull-loop-unification-design.md，原 AnnouncementCollectTask
 * 的任务化改造）：消费自循环工作队列 task.announcement.collect.q 执行一轮按订阅快照的
 * 采集，finally 中续种 + 手动 ack（顺序同 ClsPullConsumer，L3）。
 * <p>以本地快照缓存为标的源（首帧快照到达前缓存为空 → 本轮空转，续种照常——
 * 空转也是一轮，循环不能断）。双重门控：collector.enabled × announcement.enabled
 * （防未显式配置的进程打真实 CNINFO）。单标的异常隔离，不中断整批。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "datasvc.collector", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "datasvc.collector.announcement", name = "enabled", havingValue = "true")
public class AnnouncementCollectConsumer {

    private final SubscriptionSnapshotCache snapshotCache;
    private final AnnouncementCollectorService collectorService;
    private final PullLoopRenewer renewer;
    private final PullLoopProperties props;

    @RabbitListener(queues = MqQueue.TASK_ANNOUNCEMENT_COLLECT,
            containerFactory = "collectorControlListenerFactory")
    public void onCollect(Message message,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            SnapshotState snapshot = snapshotCache.get();
            if (snapshot.stocks().isEmpty()) {
                log.info("订阅快照缓存为空（version={}），本轮公告采集空转", snapshot.version());
                return;
            }
            int published = 0;
            for (SubscriptionSnapshotPayload.SnapshotStock stock : snapshot.stocks()) {
                try {
                    published += collectorService.collectStock(stock);
                } catch (Exception e) {
                    log.warn("公告采集失败（单标的隔离）stockId={}: {}", stock.getStockId(), e.getMessage());
                }
            }
            log.info("公告采集轮次结束 version={} stocks={} published={}",
                    snapshot.version(), snapshot.stocks().size(), published);
        } catch (Exception e) {
            log.warn("公告采集本轮失败（下一轮续种照常）: {}", e.toString());
        } finally {
            renewer.renew(MqKey.TASK_ANNOUNCEMENT_COLLECT, MqQueue.TASK_ANNOUNCEMENT_COLLECT_DELAY,
                    MqKey.TASK_ANNOUNCEMENT_COLLECT_DELAY,
                    props.getAnnouncement().isEnabled(), props.getAnnouncement().getTtlMs());
            try {
                channel.basicAck(deliveryTag, false);
            } catch (Exception ackError) {
                log.error("failed to ack announcement collect task", ackError);
            }
        }
    }
}
