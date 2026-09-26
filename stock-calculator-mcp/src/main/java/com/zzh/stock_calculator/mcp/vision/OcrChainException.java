package com.zzh.stock_calculator.mcp.vision;

/**
 * OCR 业务异常：经 MCP 工具面返回给调用方的确定性错误语义（等价 main 侧 common.BusinessException
 * 的 400/503 分支；mcp 模块不依赖 main common，独立定义同形状）。
 */
public class OcrChainException extends RuntimeException {

    private final int code;

    public OcrChainException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
