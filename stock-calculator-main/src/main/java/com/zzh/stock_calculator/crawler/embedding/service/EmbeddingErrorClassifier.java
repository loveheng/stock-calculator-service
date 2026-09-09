package com.zzh.stock_calculator.crawler.embedding.service;

import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.errors.UnexpectedStatusCodeException;

/**
 * 嵌入异常三分类（设计文档 §6.1），供回填 Task / 增量监听决定熔断动作。
 * openai-java 的异常类型经 OpenAiEmbeddingModel 原样透传（无包装），按 statusCode/类型判定：
 * <ul>
 *   <li>RATE_LIMITED：429（openai-java 内置重试已在 client 构建时 maxRetries=0 关闭）</li>
 *   <li>TRANSIENT：5xx / IO / 连接异常（可退避重试）</li>
 *   <li>PERMANENT：400/401/403 等确定性失败（重试无意义）与未知异常兜底</li>
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
        if (error instanceof OpenAIIoException || error instanceof InternalServerException) {
            return ErrorType.TRANSIENT;
        }
        if (error instanceof UnexpectedStatusCodeException e) {
            return e.statusCode() >= 500 ? ErrorType.TRANSIENT : ErrorType.PERMANENT;
        }
        return ErrorType.PERMANENT;
    }

    /**
     * 401/403 判定：token 失效/权限缺失属整体配置错误，应 fatal 停机人工介入；
     * 区别于单篇 400 脏数据（走 PERMANENT 计次落 FAILED 终态，不停机）。
     */
    public static boolean isFatalAuth(Throwable error) {
        if (error instanceof UnauthorizedException || error instanceof PermissionDeniedException) {
            return true;
        }
        if (error instanceof UnexpectedStatusCodeException e) {
            int code = e.statusCode();
            return code == 401 || code == 403;
        }
        return false;
    }
}
