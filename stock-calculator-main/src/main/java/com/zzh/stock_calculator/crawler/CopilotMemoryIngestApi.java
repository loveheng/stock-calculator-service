package com.zzh.stock_calculator.crawler;

import com.zzh.stockcalc.contract.message.MemoryExtractTickPayload;
import com.zzh.stockcalc.contract.message.MemoryExtractedResult;
import com.zzh.stockcalc.contract.message.MemoryProfileResult;

/**
 * copilot 记忆链入库端口（docs/copilot/memory-profile.md §五）：crawler 基包 MQ 消费中枢
 * （ClsArticleMqConsumer）把 tick / 提炼结果 / 画像结果交回 copilot 域处理。
 * 端口按 AnnouncementIngestApi 先例宿主在 crawler 基包（消费方定义端口、业务域实现，
 * 依赖单向 copilot → crawler）——放 copilot 基包会成环（ModulithVerifyTest 实证拦截）。
 * 实现内部自吞业务异常（固化链路失败绝不影响对话主链路，§九失败隔离）。
 */
public interface CopilotMemoryIngestApi {

    /**
     * result.memory.extract.tick：在途锁 CAS → 差量重算（空 drop）→ 发布提炼任务。
     */
    void onExtractTick(MemoryExtractTickPayload payload);

    /**
     * result.memory.extracted：窗口记忆 upsert + 水位推进清锁 + ΔCount 统计与画像触发。
     */
    void onExtracted(MemoryExtractedResult payload);

    /**
     * result.memory.profile：画像覆盖入库 + version+1 + 游标推进至快照 max(updated_at)。
     */
    void onProfile(MemoryProfileResult payload);
}
