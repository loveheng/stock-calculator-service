package com.zzh.stock_calculator.auth.dto;
import lombok.Data;
import lombok.ToString;

/**
 * 找回确认请求（《设计》§D.4.2 端点 8，决策 B6 原子化）。
 *
 * @description newPassword 为新 authHash（64 位 hex）；recovery_payload 不在本请求中（助记词未更换）。
 */
@Data
public class RecoveryConfirmRequest {

    @ToString.Exclude
    private String newPassword;

    private String passwordPayload;

    private String passwordIv;
}
