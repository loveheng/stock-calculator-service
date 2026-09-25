package com.zzh.stock_calculator.data.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * worker 角色配置（datasvc.worker 前缀，设计文档 §4.5/§5）。
 * enabled 缺省 false：@ConditionalOnProperty 无 matchIfMissing 是项目既有约定
 * （与 collector 门控同款，缺省不装配）。native 构建须在构建环境固定
 * datasvc.worker.enabled=true（AOT 固化条件装配，R1 教训，collector 同）。
 */
@Data
@ConfigurationProperties(prefix = "datasvc.worker")
public class WorkerProperties {

    /** worker 角色总开关 */
    private boolean enabled = false;

    private final Prefetch prefetch = new Prefetch();

    private final Embedding embedding = new Embedding();

    @Data
    public static class Prefetch {

        /** 向量化任务竞争消费 prefetch（设计文档 §5：副本内单消费者一次预取 8 条） */
        private int embedding = 8;

        /** 公告处理任务 prefetch（阶段 4 接入时使用） */
        private int announcement = 2;

        /**
         * 公告任务监听器并发消费者数（默认 1 = 原行为）。PDF+LLM 均为外部 IO 等待，
         * 提并发近似线性提单副本吞吐；注意 50MB PDF × 并发的 RSS 峰值（2~3 并发建议
         * 预留 1~2GB 内存余量）。运行期可经 DATASVC_WORKER_PREFETCH_ANNOUNCEMENTCONCURRENCY
         * 覆盖（值非 AOT 条件，env 可改）。
         */
        private int announcementConcurrency = 1;
    }

    @Data
    public static class Embedding {

        /** 每实例请求节流（次/分钟）：~3.3 req/s，与原进程内回填 64 条/300ms 批节奏同量级 */
        private int rateLimitPerMinute = 200;

        // 供应商连接规格（凭据/model/dimensions/timeout）已迁 ai.embeddings.embed
        // （stock-calculator-llm 组件，provider 可切换，不再绑定 CF）
    }
}
