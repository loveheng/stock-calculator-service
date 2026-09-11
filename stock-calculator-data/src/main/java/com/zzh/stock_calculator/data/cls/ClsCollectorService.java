package com.zzh.stock_calculator.data.cls;

import com.zzh.stockcalc.contract.MessageType;
import com.zzh.stockcalc.contract.message.ClsArticlePayload;
import com.zzh.stock_calculator.data.mq.ResultPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * CLS 电报采集编排（阶段 1 滚动窗口链路，设计文档 D3）：
 * 拉 cache 接口 → 提取 roll_data → 逐条解析为契约 DTO → result.cls.article 上行。
 * 去重移交主服务幂等入库（saveIfNotExists 语义平移），本地无任何状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClsCollectorService {

    private final ClsHttpService httpService;
    private final ResultPublisher resultPublisher;

    /** 单轮采集：返回成功上行的条数 */
    public int pullAndPublish() {
        Map<String, Object> result = httpService.getForMap(
                ClsApiParams.CACHE_URL, ClsApiParams.cacheParams(), ClsApiParams.header());
        List<?> rollList = extractRollData(result);
        if (rollList.isEmpty()) {
            log.info("CLS pull: empty roll_data, skip");
            return 0;
        }

        int published = 0;
        for (Object item : rollList) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            try {
                ClsArticlePayload payload = ClsArticleParser.parse(ClsValueUtil.coerceMap(raw));
                if (payload == null) {
                    continue; // id 缺失，无法作为幂等锚点
                }
                resultPublisher.publish(MessageType.RESULT_CLS_ARTICLE, payload);
                published++;
            } catch (Exception e) {
                // 单条隔离：解析/发布失败不中断整轮
                log.warn("failed to parse/publish roll item, id={}", raw.get("id"), e);
            }
        }
        log.info("CLS pull done: fetched={} published={}", rollList.size(), published);
        return published;
    }

    /** 安全提取 data.roll_data（对齐原 ClsDayTask.getCacheData 的双层 instanceof 校验） */
    private static List<?> extractRollData(Map<String, Object> result) {
        if (result == null) {
            return Collections.emptyList();
        }
        if (!(result.get("data") instanceof Map<?, ?> dataMap)) {
            return Collections.emptyList();
        }
        if (!(dataMap.get("roll_data") instanceof List<?> rollList)) {
            return Collections.emptyList();
        }
        return rollList;
    }
}
