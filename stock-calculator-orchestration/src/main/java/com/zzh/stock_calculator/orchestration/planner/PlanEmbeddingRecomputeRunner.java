package com.zzh.stock_calculator.orchestration.planner;

import com.zzh.stock_calculator.orchestration.entity.PlanEntity;
import com.zzh.stock_calculator.orchestration.repository.PlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 存量 plan 向量锚统一重算（P4① 锚混用治愈，一次性运维开关）：
 * intent_template 模板锚上线前的存量行 embedding 以 intent_text 原句嵌出，与新模板锚
 * 向量混用会导致复用匹配距离偏置 + draft 孪生去重漏网。本 Runner 全量重算
 * （锚 = intent_template 优先，缺失降级 intent_text），一次性拉齐口径。
 * <p>默认关闭（orchestration.embedding.recompute-all=false）；执行方式：配置临时置 true
 * 启动一次，完成后改回。embedding 调用失败的行保留原向量并留 warn（可重跑）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanEmbeddingRecomputeRunner implements ApplicationRunner {

    private final PlanRepository planRepository;
    private final IntentEmbeddingClient embeddingClient;

    @Value("${orchestration.embedding.recompute-all:false}")
    private boolean recomputeAll;

    @Override
    public void run(ApplicationArguments args) {
        if (!recomputeAll) {
            return;
        }
        List<PlanEntity> plans = planRepository.findAll();
        int ok = 0;
        int skip = 0;
        int fail = 0;
        for (PlanEntity p : plans) {
            if ("deprecated".equals(p.getStatus())) {
                skip++;
                continue;
            }
            // 与 Planner.plan 同口径：模板锚优先，缺失降级 canonical 原句
            String anchor = p.getIntentTemplate() != null && !p.getIntentTemplate().isBlank()
                    ? p.getIntentTemplate() : p.getIntentText();
            try {
                String qv = com.zzh.llm.EmbeddingVectorLiteral.of(embeddingClient.embed(anchor));
                planRepository.updateEmbedding(p.getId(), qv);
                ok++;
            } catch (RuntimeException e) {
                fail++;
                log.warn("[orchestration] plan {} 锚重算失败（保留原向量）: {}", p.getId(), e.getMessage());
            }
        }
        log.info("[orchestration] 存量 plan 锚统一重算完成: total={} ok={} skip(deprecated)={} fail={}",
                plans.size(), ok, skip, fail);
    }
}
