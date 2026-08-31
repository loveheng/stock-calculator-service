package com.zzh.stock_calculator.dto.auth;

import lombok.Data;

/** 找回第一步：请求发送验证码（《设计》§D.4.2 端点 6）；响应恒 200，不泄露邮箱存在性 */
@Data
public class RecoveryEmailRequest {

    private String email;
}
