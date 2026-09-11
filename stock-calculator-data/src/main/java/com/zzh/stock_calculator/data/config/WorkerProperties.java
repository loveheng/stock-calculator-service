package com.zzh.stock_calculator.data.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

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
    }

    @Data
    public static class Embedding {

        /** CF 账户 ID，拼接 OpenAI 兼容端点 base-url */
        private String accountId;

        /** CF API Token（日志脱敏） */
        private String apiToken;

        /** Workers AI embedding 模型（与主服务发布端/额度口径同款） */
        private String model = "@cf/baai/bge-m3";

        /** 向量维度（bge-m3 固定 1024，主服务落库前置校验同值） */
        private int dimensions = 1024;

        /** 每实例请求节流（次/分钟）：~3.3 req/s，与原进程内回填 64 条/300ms 批节奏同量级 */
        private int rateLimitPerMinute = 200;

        /** 单次 CF HTTP 请求超时（支持 30s/500ms 等写法） */
        private Duration readTimeout = Duration.ofSeconds(30);
    }
}
