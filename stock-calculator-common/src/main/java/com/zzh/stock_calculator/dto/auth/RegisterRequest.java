package com.zzh.stock_calculator.dto.auth;

import lombok.Data;
import lombok.ToString;

/**
 * 注册请求（《设计》§D.4.2 端点 1）。
 *
 * @description password 即前端 authHash（64 位小写 hex）；toString 脱敏（零知识红线，决策 B2）。
 */
@Data
public class RegisterRequest {

    private String email;

    @ToString.Exclude
    private String password;
}
