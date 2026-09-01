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
}
