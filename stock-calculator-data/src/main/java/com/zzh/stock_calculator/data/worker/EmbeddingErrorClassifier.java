package com.zzh.stock_calculator.data.worker;

import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnexpectedStatusCodeException;

/**
 * 嵌入异常三分类（设计文档 §6.1，worker 侧唯一实现；main 侧副本已随回退路径删除）：
 * openai-java 异常经 OpenAiEmbeddingModel 原样透传（client 构建时 maxRetries=0 关闭内置重试）。
 * worker 据此决定消息分流（§4.2/§4.4）：
 * <ul>
 *   <li>RATE_LIMITED（429）：CF 当日额度耗尽 → ack 丢弃不回报（契约无 failed 通道），
 *       状态行留 PENDING → 主服务对账器次日续发</li>
 *   <li>TRANSIENT（5xx/IO/中断）：nack 进 TTL 重试环，30s 后快速自愈</li>
 *   <li>PERMANENT（400 等确定性失败与未知兜底）：ack 丢弃，对账器兜底</li>
 * </ul>
 */
public final class EmbeddingErrorClassifier {

    public enum ErrorType {
        RATE_LIMITED,
        TRANSIENT,
        PERMANENT
    }

    private EmbeddingErrorClassifier() {
    }

    public static ErrorType classify(Throwable error) {
        if (error instanceof RateLimitException) {
            return ErrorType.RATE_LIMITED;
        }
        // 中断（优雅停机/连接关闭）属外部故障：消息未 ack 会随容器重投
        if (error instanceof InterruptedException || error instanceof OpenAIIoException
                || error instanceof InternalServerException) {
            return ErrorType.TRANSIENT;
        }
        if (error instanceof UnexpectedStatusCodeException e) {
            return e.statusCode() >= 500 ? ErrorType.TRANSIENT : ErrorType.PERMANENT;
        }
        return ErrorType.PERMANENT;
    }

    /**
     * 401/403 判定：token 失效/权限缺失属整体配置错误 → 任务直接投 dead.q 停放
     * （修复凭据后可人工重放），区别于单篇 400 脏数据（丢弃，对账器兜底）。
     */
    public static boolean isFatalAuth(Throwable error) {
        return error instanceof com.openai.errors.UnauthorizedException
                || error instanceof com.openai.errors.PermissionDeniedException
                || (error instanceof UnexpectedStatusCodeException e
                        && (e.statusCode() == 401 || e.statusCode() == 403));
    }
}
