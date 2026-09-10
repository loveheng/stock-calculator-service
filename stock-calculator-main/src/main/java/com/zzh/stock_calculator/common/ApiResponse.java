package com.zzh.stock_calculator.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder().code(200).message("success").data(data).build();
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return ApiResponse.<T>builder().code(code).message(message).data(null).build();
    }

    /** fail + data 重载（429 信封携带 data.retryAfterSeconds，backend-implementation §5.2）；既有两参重载保持不变 */
    public static <T> ApiResponse<T> fail(int code, String message, T data) {
        return ApiResponse.<T>builder().code(code).message(message).data(data).build();
    }
}
