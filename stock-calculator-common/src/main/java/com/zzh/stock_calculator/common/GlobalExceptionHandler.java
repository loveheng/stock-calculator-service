package com.zzh.stock_calculator.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return ApiResponse.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ApiResponse<Void> handleMaxSizeException(MaxUploadSizeExceededException e) {
        return ApiResponse.fail(400, "上传文件大小超过系统上限");
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleGenericException(Exception e) {
        log.error("系统未知异常", e);
        return ApiResponse.fail(500, "截图解析服务暂不可用，请稍后重试");
    }
}
