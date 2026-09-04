package com.zzh.stock_calculator.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return ApiResponse.fail(e.getCode(), e.getMessage());
    }

    /**
     * SSE/异步请求生命周期异常：客户端中途断开（容器把 Broken pipe 包装为本异常，
     * 经 async error dispatch 进入 advice）或流超时（AsyncRequestTimeoutException）。
     * 此时响应已按 text/event-stream 提交且连接不可写，无法回落 JSON 信封——
     * 必须返回 void 静默收尾（@RestControllerAdvice 的 void 走 RequestResponseBodyMethodProcessor，
     * null body 不写不报错）；若返回 ApiResponse 会因 "No converter ... preset
     * Content-Type 'text/event-stream'" 触发二次告警。只打一行 WARN，不带堆栈。
     */
    @ExceptionHandler({AsyncRequestNotUsableException.class, AsyncRequestTimeoutException.class})
    public void handleAsyncUnavailable(Exception e) {
        log.warn("SSE/异步请求终止（客户端断开或超时）: {}", e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ApiResponse<Void> handleMaxSizeException(MaxUploadSizeExceededException e) {
        return ApiResponse.fail(400, "上传文件大小超过系统上限");
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleGenericException(Exception e) {
        log.error("系统未知异常", e);
        return ApiResponse.fail(500, "系统繁忙，请稍后重试");
    }
}
