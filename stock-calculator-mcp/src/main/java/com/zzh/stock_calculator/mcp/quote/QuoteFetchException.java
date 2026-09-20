package com.zzh.stock_calculator.mcp.quote;

/** 行情拉取失败（接口异常/代码无数据），工具层捕获转错误响应 */
public class QuoteFetchException extends RuntimeException {

    public QuoteFetchException(String message) {
        super(message);
    }

    public QuoteFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
