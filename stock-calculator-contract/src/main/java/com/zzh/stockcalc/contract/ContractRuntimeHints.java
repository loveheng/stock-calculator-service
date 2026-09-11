package com.zzh.stockcalc.contract;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.zzh.stockcalc.contract.message.*;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * 契约 DTO 的 native-image 反射注册（设计文档 R2；2026-09-11 data 模块 R1 冒烟实证）：
 * 消费方在监听器内手工 readValue/convertValue 还原信封与 payload，Spring AOT 无法从
 * 代码签名推断这些类型，缺注册则 native 反序列化报 InvalidDefinitionException
 * "no delegate- or property-based Creator"（JVM 不受影响，仅 native 必现）。
 * 【新增消息 DTO 必须同步登记 DTO_TYPES，否则 native 消费端解析失败】；
 * 两侧模块经 @ImportRuntimeHints 引用本类（data 挂 MqTopologyConfig、main 挂
 * ContractHintsConfig），DTO→hints 映射保持契约模块单点。
 * 全量 MemberCategory 覆盖构造器/字段/getter，序列化与反序列化一并满足；
 * getNestMembers 递归兜底 DTO 内部类（含 Lombok @Builder 生成类）。
 */
public class ContractRuntimeHints implements RuntimeHintsRegistrar {

    /** 信封 + 全部消息 DTO（com.zzh.stockcalc.contract.message 包），新 DTO 需登记于此 */
    private static final List<Class<?>> DTO_TYPES = List.of(
            MessageEnvelope.class,
            AnnouncementCollectedPayload.class,
            AnnouncementDonePayload.class,
            AnnouncementFailedPayload.class,
            AnnouncementProcessTask.class,
            ArticleIngestedPayload.class,
            ClsArticleDto.class,
            ClsArticlePayload.class,
            ClsHistoryReport.class,
            ClsStockDict.class,
            ClsStockLink.class,
            ClsSubjectDict.class,
            ClsSubjectLink.class,
            EmbeddingComputeResult.class,
            EmbeddingComputeTask.class,
            HistorySyncTask.class,
            IngestArticleIds.class,
            SliceSelection.class,
            StructureNode.class,
            SubscriptionSnapshotPayload.class);

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        Set<Class<?>> seen = new HashSet<>();
        for (Class<?> type : DTO_TYPES) {
            register(hints, type, seen);
        }
    }

    private void register(RuntimeHints hints, Class<?> type, Set<Class<?>> seen) {
        if (!seen.add(type)) {
            return;
        }
        hints.reflection().registerType(type, MemberCategory.values());
        for (Class<?> nested : type.getNestMembers()) {
            register(hints, nested, seen);
        }
    }
}
