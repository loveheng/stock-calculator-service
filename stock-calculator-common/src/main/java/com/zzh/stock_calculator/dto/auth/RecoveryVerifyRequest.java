package com.zzh.stock_calculator.dto.auth;

import lombok.Data;

/** 找回第二步：验证码校验（《设计》§D.4.2 端点 7）；通过即签发 recovery 受限会话 */
@Data
public class RecoveryVerifyRequest {

    private String email;

    private String code;
}
