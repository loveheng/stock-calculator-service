package com.zzh.stock_calculator.auth.dto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 密文档案读取响应（《设计》§D.4.2 端点 4）：四密文 + updatedAt。
 *
 * @description updatedAt 为 ISO-8601 字符串，前端在后续 PUT 中原样回传为 If-Match（决策 B5）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileResponse {

    private String passwordPayload;

    private String passwordIv;

    private String recoveryPayload;

    private String recoveryIv;

    private String updatedAt;
}
